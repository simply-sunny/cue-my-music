package mix.cuemymusic;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;
import mix.cuemymusic.mixin.MusicFrequencyInvoker;
import mix.cuemymusic.mixin.MusicManagerAccessor;
import mix.cuemymusic.mixin.SoundEngineAccessor;
import mix.cuemymusic.mixin.SoundManagerAccessor;

/**
 * What's playing and where, for the player screen, and the play controls.
 *
 * <p>Every music stream reports itself here when it starts and closes. Pause,
 * seek and resume all restart the track at a position: streams can't rewind,
 * but skipping ahead is fast.
 *
 * <p>The game still decides when its music plays, but every song it starts
 * goes through here (see {@code MusicManagerMixin}). The song it will play
 * next and the break before it are picked ahead of time, with the game's own
 * rules, so the queue can show them.
 *
 * <p>Songs the player picks aren't cut off by the game: they keep playing
 * through dimension changes and menus, or pause while a new dimension loads
 * if that's set. Songs added to the queue play as soon as the current one
 * ends, then the rest of the list a song was started from (see
 * {@link PlayOrder}); after that the game's schedule takes over again.
 */
public final class CuePlayer {
    public static final CuePlayer INSTANCE = new CuePlayer();
    /** The list key for all game music, which Next falls back to when the game has no music to play. */
    public static final String ALL = "all";
    /** The list key Radio plays under. */
    public static final String RADIO = "radio";
    /** Radio adds this many songs at a time, whenever fewer than {@link #RADIO_AHEAD} are left. */
    private static final int RADIO_BATCH = 20, RADIO_AHEAD = 3;
    private static final AtomicLong STREAM_IDS = new AtomicLong();
    /** How long a track may take to start before it's treated as failed. */
    private static final long START_TIMEOUT_NANOS = 3_000_000_000L;

    private volatile Track current;
    private volatile double position;
    private volatile boolean playing;
    private volatile boolean paused;
    private volatile TrackSoundInstance ours;
    /** The music event the current track plays for. */
    private Identifier event = TrackSoundInstance.GAME;
    /** True if the current track was picked in the player rather than by the game. */
    private boolean picked;
    /** The stream that's playing; -1 once it was stopped on purpose, so its close isn't taken as the song ending. */
    private long activeStream = -1;
    private boolean ended;
    private long oursStarted;
    private final PlayOrder<Track> order = new PlayOrder<>();
    /** True while the current track is paused for a dimension change, to resume once the new level has loaded. */
    private boolean travelHold;
    /** When the new level was first seen ready after a dimension change, or 0. */
    private long travelReadySince;
    private static final long TRAVEL_SETTLE_NANOS = 500_000_000L;
    /** Seconds the song fades back in over once the new dimension is on screen. */
    private static final double TRAVEL_FADE_SECONDS = 2;
    /** Seconds the song fades out over when a level changes with no portal to fade through first. */
    private static final double TRAVEL_OUT_SECONDS = 0.5;
    /** True while a level is loaded, so a level change can tell travel from joining or leaving a world. */
    private boolean inWorld;
    /** When the fade out for a level change began, and the volume it began from; 0 with none under way. */
    private long travelOutStart;
    private float travelOutFrom;
    /** The song's fade for travel right now, from 1 (full) to 0. */
    private float travelGain = 1;
    private long lastNanos;
    /** The song an automixed song blends into, or null; read by the stream. */
    private volatile Track automixNext;

    /** A stream blended into {@code track}, which counts as playing from {@code atNanos}. */
    private record Handover(long stream, Track track, double seconds, long atNanos) {
    }

    private volatile Handover handover;
    /** Set when a song ends by itself, so the next start counts as a new play even for the same song. */
    private boolean replay;
    /** True while the music is paused because the game window lost focus, so it plays again when it's back. */
    private boolean focusPaused;
    // Radio: what it picks with, the song it started from (null for everything), and its songs so far.
    private final Radio radio = new Radio(System.nanoTime());
    private Track radioSeed;
    private List<Track> radioTracks = List.of();
    // A transition being previewed: its first song, and the song and point in it where the preview stops.
    private Track previewFrom, previewTo;
    private double previewUntil;

    // The game's next song, planned ahead.
    private final RandomSource random = RandomSource.create();
    private Music plannedFor;
    private MusicManager.MusicFrequency plannedFrequency;
    /** The game's pick: one draw from the music's sounds, exactly as the game makes it. Null if there's no music. */
    private Sound plannedSound;
    /** The pick as a track, or null if the game picked silence (an empty entry). */
    private Track planned;
    private int plannedBreak;

    private CuePlayer() {
    }

    public Track current() {
        return current;
    }

    /** Seconds into the track, in the track's own time. */
    public double position() {
        return position;
    }

    public boolean isPlaying() {
        return playing && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    /** True while other music should stay quiet: one of the player's tracks is playing, starting or paused. */
    public boolean holdsMusic() {
        return paused || ours != null;
    }

    /** True if {@code instance} is the player's current track. */
    public boolean isOurs(SoundInstance instance) {
        return instance != null && instance == ours;
    }

    /** True if {@code instance} is a track picked in the player, which the game shouldn't stop. */
    public boolean isPicked(SoundInstance instance) {
        return isOurs(instance) && picked;
    }

    /**
     * True if the game may cut the song playing for {@code music}: only for the
     * dragon fight and the credits. Anything else, such as the menu music a
     * loading screen asks for, waits for the song to end.
     */
    public static boolean replacesSong(Music music) {
        String path = music.sound().value().location().getPath();
        return path.equals("music.dragon") || path.equals("music.credits");
    }

    // Called from the stream-loading thread.

    static long nextStreamId() {
        return STREAM_IDS.incrementAndGet();
    }

    /** Returns false for a stream that's about to be stopped, which isn't shown. */
    boolean streamStarted(long id, Identifier location, double offset, SoundInstance instance) {
        // Music from somewhere else that was already loading when the player took over: it gets stopped, don't show it.
        if (holdsMusic() && !isOurs(instance)) return false;
        synchronized (this) {
            activeStream = id;
            current = MusicLibrary.get(location);
            position = offset;
            playing = true;
            paused = false;
            ended = false;
            lastNanos = System.nanoTime();
        }
        return true;
    }

    void streamClosed(long id) {
        synchronized (this) {
            if (id != activeStream) return;
            activeStream = -1;
            playing = false;
            ended = true;
        }
    }

    // Client thread.

    public void tick() {
        long now = System.nanoTime();
        boolean finished;
        double heardSeconds = 0;
        applyJump(now);
        handOver(now);
        if (CueStream.takeSkipFailed()) cutToNext();
        followFocus();
        synchronized (this) {
            if (playing && !paused) {
                heardSeconds = Math.min(1, (now - lastNanos) / 1e9);
                // The speed being heard: a change takes a few seconds to reach the speakers.
                CueStream.Params heard = CueStream.heard();
                position += (now - lastNanos) / 1e9 * (heard != null ? heard.tempo() : CueConfig.INSTANCE.tempo());
                Track t = current;
                if (t != null && !Double.isNaN(t.seconds())) position = Math.min(position, t.seconds());
            }
            lastNanos = now;
            finished = ended;
            ended = false;
        }
        Track heardTrack = current;
        Minecraft mc = Minecraft.getInstance();
        // Music turned all the way down isn't heard.
        if (heardSeconds > 0 && heardTrack != null && mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC) > 0) {
            CueConfig cfg = CueConfig.INSTANCE;
            boolean effects = cfg.enabled && (cfg.pitchSemitones != 0 || cfg.speed != 1.0 || cfg.eqEnabled);
            ListeningStats.INSTANCE.listened(heardTrack.location().toString(), heardSeconds, java.time.LocalDateTime.now(),
                    mc.level != null ? mc.level.dimension().identifier().toString() : null, picked, effects);
            StatsFile.saveNowAndThen();
        }
        followPreview();
        plan();
        keepRadioGoing();
        autoplay();
        planAutomix();
        fadeForTravel(now);
        OutsideAudio.volume(Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.MUSIC) * travelGain);
        if (finished) {
            if (current != null) ListeningStats.INSTANCE.finished(current.location().toString(), current.seconds());
            ours = null;
            // What plays next is a fresh play, even if it's the same song again (repeat one).
            replay = true;
            dropMissing();
            order.setRepeat(repeat());
            Track n = order.finished(current);
            if (n != null) start(n, 0, TrackSoundInstance.GAME, true);
            return;
        }
        if (travelHold) {
            // Wait until the new level is on screen and has stayed so for a moment, then fade back in.
            if (!levelReady()) {
                travelReadySince = 0;
            } else if (travelReadySince == 0) {
                travelReadySince = now;
            } else if (now - travelReadySince >= TRAVEL_SETTLE_NANOS) {
                travelHold = false;
                travelReadySince = 0;
                if (paused && current != null) {
                    CueStream.fadeInNext(TRAVEL_FADE_SECONDS);
                    start(current, position, event, picked);
                }
            }
        }
        TrackSoundInstance o = ours;
        if (o != null && !playing && !paused && now - oursStarted > START_TIMEOUT_NANOS
                && !Minecraft.getInstance().getSoundManager().isActive(o)) {
            ours = null;
        }
    }

    /**
     * Pauses the music while the game window isn't in front, and plays it again
     * when it is, if the Pause in background setting is on. With it off, the
     * music carries on in the background.
     */
    private void followFocus() {
        Minecraft mc = Minecraft.getInstance();
        boolean background = !mc.isWindowActive() && CueConfig.INSTANCE.pauseOnLostFocus;
        if (background) {
            if (!focusPaused && ours != null && isPlaying()) {
                focusPaused = true;
                togglePause();
            }
        } else if (focusPaused) {
            focusPaused = false;
            if (paused) togglePause();
        }
    }

    // Radio.

    /** The songs Radio plays from: the mode's music, the game's in Vanilla + FX and the add-ons' in Add-ons. */
    private static List<Track> radioPool() {
        return Modes.addOnsActive() ? MusicLibrary.addOnMusic() : MusicLibrary.gameMusic();
    }

    /** What Radio knows of {@code t}, from its likes, stats and (for add-on songs) its tempo and key. */
    private static Radio.Song radioSong(Track t, java.util.Map<String, ListeningStats.Song> stats) {
        String id = t.location().toString();
        ListeningStats.Song s = stats.get(id);
        Automix.Profile p = AutomixProfiles.applies(t) ? AutomixProfiles.get(t) : null;
        CueConfig cfg = CueConfig.INSTANCE;
        return new Radio.Song(id, t.artist(), t.album(), p != null ? p.bpm() : 0, p != null ? p.key() : "", cfg.isLiked(id),
                cfg.isDisliked(id), s != null ? s.plays : 0, s != null ? s.skips : 0);
    }

    /** {@code count} more songs for Radio, after {@code played}. */
    private List<Track> radioPicks(List<Track> played, int count) {
        return radioPicks(played, count, radioSeed);
    }

    private List<Track> radioPicks(List<Track> played, int count, Track from) {
        List<Track> pool = radioPool();
        java.util.Map<String, ListeningStats.Song> stats = ListeningStats.INSTANCE.songs();
        java.util.Map<String, Track> byId = new java.util.HashMap<>();
        for (Track t : pool) byId.put(t.location().toString(), t);
        List<Radio.Song> songs = pool.stream().map(t -> radioSong(t, stats)).toList();
        Radio.Song seed = from != null ? radioSong(from, stats) : null;
        List<String> history = played.stream().map(t -> t.location().toString()).toList();
        return radio.next(songs, seed, history, count).stream().map(s -> byId.get(s.id())).toList();
    }

    /** Radio's songs: the ones playing through, or the ones it would play if started now. */
    public List<Track> radioTracks() {
        if (radioTracks.isEmpty()) {
            List<Track> first = new java.util.ArrayList<>();
            if (radioSeed != null) first.add(radioSeed);
            first.addAll(radioPicks(first, RADIO_BATCH));
            radioTracks = List.copyOf(first);
        }
        return radioTracks;
    }

    /** The song Radio started from, or null if it plays from everything. */
    public Track radioSeed() {
        return radioSeed;
    }

    /** Radio picks from everything from here on, rather than from the song it started from. */
    public void clearRadioSeed() {
        radioSeed = null;
        refreshRadio();
    }

    /** Starts Radio from {@code seed}, or from everything if that's null. */
    public void startRadio(Track seed) {
        radioSeed = seed;
        radioTracks = List.of();
        List<Track> tracks = radioTracks();
        if (!tracks.isEmpty()) play(RADIO, tracks, 0);
    }

    /** Picks Radio's songs again, as when its music changes; while it plays, from after the song that's on. */
    public void refreshRadio() {
        if (isPlayingList(RADIO)) {
            List<Track> list = order.list();
            List<Track> kept = list.subList(0, Math.min(list.size(), list.size() - order.remaining()));
            List<Track> rest = radioPicks(kept, RADIO_BATCH);
            order.replaceRest(rest);
            radioTracks = order.list();
        } else {
            radioTracks = List.of();
        }
    }

    /** The last song of a list that Autoplay looked for more after, so it looks once. */
    private Track autoplayed;

    /**
     * Autoplay, as on Spotify: when a list reaches its last song with nothing
     * queued and repeat off, Radio's picks for that song are added to the
     * end, so music carries on instead of handing back to the game.
     */
    private void autoplay() {
        Track c = current;
        if (!CueConfig.INSTANCE.autoplay || c == null || c.equals(autoplayed) || order.listKey() == null || isPlayingList(RADIO)
                || repeat() != PlayOrder.Repeat.OFF || order.remaining() > 0 || !order.queue().isEmpty()) return;
        autoplayed = c;
        List<Track> more = radioPicks(order.list(), RADIO_BATCH, c);
        if (!more.isEmpty()) order.extendList(more);
    }

    /** While Radio plays, adds songs before it runs out, so it never ends. */
    private void keepRadioGoing() {
        if (!isPlayingList(RADIO)) return;
        if (order.remaining() < RADIO_AHEAD) {
            List<Track> more = radioPicks(order.list(), RADIO_BATCH);
            if (!more.isEmpty()) order.extendList(more);
        }
        radioTracks = order.list();
    }

    // Automix.

    /** The song the playing one blends into, if both are automixed. Read by the stream. */
    Track automixNext() {
        return automixNext;
    }

    /** The song Next blended out of, so it counts as skipped rather than heard through. */
    private Track skippedByBlend;

    /** Called from the stream: it's blending into {@code track}, which is heard as the song playing from {@code atNanos}. */
    void automixed(long stream, Track track, double seconds, long atNanos) {
        handover = new Handover(stream, track, seconds, atNanos);
    }

    /** A jump in the playing song, from a skip with a transition, and when it's heard. */
    private record Jump(long stream, double seconds, long atNanos) {
    }

    private volatile Jump jump;

    /** Called from the stream: it jumped to {@code seconds} into the song, heard from {@code atNanos}. */
    void jumped(long stream, double seconds, long atNanos) {
        jump = new Jump(stream, seconds, atNanos);
    }

    /** The jump is heard now: the song's time moves with it. */
    private void applyJump(long now) {
        Jump j = jump;
        if (j == null || now < j.atNanos()) return;
        jump = null;
        if (j.stream() != activeStream) return;
        synchronized (this) {
            position = j.seconds() + (now - j.atNanos()) / 1e9 * CueConfig.INSTANCE.tempo();
            lastNanos = now;
        }
    }

    /**
     * What plays after the current song, worked out ahead so the stream can
     * start blending into it: automixed, or crossfaded when automix doesn't
     * apply to both songs.
     */
    private void planAutomix() {
        Track c = current, n = null;
        if (c != null && ours != null && repeat() != PlayOrder.Repeat.ONE) {
            order.setRepeat(repeat());
            List<Track> queued = order.queue();
            n = queued.isEmpty() ? order.listNext() : queued.getFirst();
            boolean automixed = AutomixProfiles.applies(c) && AutomixProfiles.applies(n);
            if (n != null && !automixed && CueConfig.INSTANCE.crossfadeSeconds <= 0) n = null;
            // The stream keeps the first song's sounds.json volume, so songs at another volume don't blend into it.
            // With little or no time between the game's songs, its song blends into the game's next.
            if (n == null && !picked && mixesGameSongs() && planned != null && !planned.equals(c)) n = planned;
            if (n != null && fileVolume(n) != fileVolume(c)) n = null;
            // Ready before the fade is due.
            if (AutomixProfiles.applies(c)) AutomixProfiles.get(c);
            if (AutomixProfiles.applies(n)) AutomixProfiles.get(n);
        }
        automixNext = n;
    }

    /**
     * True when the game's songs follow one another closely enough to blend:
     * the time between songs set to 5 seconds or less, or the game's Constant.
     */
    private boolean mixesGameSongs() {
        int gap = songGap();
        if (gap >= 0) return gap <= 5;
        return Minecraft.getInstance().options.musicFrequency().get() == MusicManager.MusicFrequency.CONSTANT;
    }

    /** The volume sounds.json plays {@code track} at, as a share of full volume. */
    private float fileVolume(Track track) {
        Sound s = MusicLibrary.sound(track.location());
        return s == null ? 1 : s.getVolume().sample(random);
    }

    /** The blended-in song is heard now: it becomes the current song, as if the last one had ended. */
    private void handOver(long now) {
        Handover h = handover;
        if (h == null || now < h.atNanos()) return;
        handover = null;
        if (h.stream() != activeStream) return;
        Track ended = current;
        if (ended != null && ended.equals(skippedByBlend)) ListeningStats.INSTANCE.skipped(ended.location().toString());
        else if (ended != null) ListeningStats.INSTANCE.finished(ended.location().toString(), ended.seconds());
        skippedByBlend = null;
        ListeningStats.INSTANCE.started(h.track().location().toString());
        order.setRepeat(repeat());
        order.finished(ended);
        // Blended into the game's next song: it's played, so the game's next one after it is picked.
        if (!picked && h.track().equals(planned)) replan();
        synchronized (this) {
            current = h.track();
            position = h.seconds() + (now - h.atNanos()) / 1e9;
            lastNanos = now;
        }
    }

    /** Plays {@code list} from {@code index} to the end, after anything queued. {@code key} names the list. */
    public void play(Object key, List<Track> list, int index) {
        // Only the mode's own songs: the one clicked, and the list it plays through.
        Track picked = list.get(index);
        if (!Modes.allows(picked)) return;
        list = Modes.only(list);
        index = list.indexOf(picked);
        previewTo = null;
        CueConfig.INSTANCE.listPlayed(String.valueOf(key));
        order.setShuffle(CueConfig.INSTANCE.shuffle);
        start(order.play(key, list, index), 0, TrackSoundInstance.GAME, true);
    }

    /** Plays all of {@code list}: from the top, or with shuffle on, from a song picked at random, as on Spotify. */
    public void playAll(Object key, List<Track> list) {
        if (list.isEmpty()) return;
        play(key, list, CueConfig.INSTANCE.shuffle ? java.util.concurrent.ThreadLocalRandom.current().nextInt(list.size()) : 0);
    }

    public boolean shuffle() {
        return CueConfig.INSTANCE.shuffle;
    }

    /** Turns shuffle on or off, for good: the list playing is mixed, or put back in order, at once. */
    public void setShuffle(boolean on) {
        CueConfig.INSTANCE.shuffle = on;
        CueConfig.INSTANCE.save();
        order.setShuffle(on);
    }

    /**
     * Plays just the transition from {@code list}'s song {@code index} into the
     * next: from {@code from} seconds into the first, pausing at {@code until}
     * seconds into the second.
     */
    public void previewTransition(Object key, List<Track> list, int index, double from, double until) {
        play(key, list, index);
        seek(from);
        previewFrom = list.get(index);
        previewTo = list.get(index + 1);
        previewUntil = until;
    }

    /** True while a transition preview is playing. */
    public boolean isPreviewing() {
        return previewTo != null && isPlaying();
    }

    /** Stops a transition preview, pausing where it is. */
    public void stopPreview() {
        if (previewTo == null) return;
        previewTo = null;
        if (isPlaying()) togglePause();
    }

    /** Pauses a preview once it's past its transition, and forgets it once something else plays. */
    private void followPreview() {
        Track to = previewTo, c = current;
        if (to == null) return;
        if (c == null || !(c.equals(to) || c.equals(previewFrom))) previewTo = null;
        else if (c.equals(to) && position >= previewUntil) stopPreview();
    }

    /** True if the list named {@code key} is the one playing through. */
    public boolean isPlayingList(Object key) {
        return key.equals(order.listKey());
    }

    public void togglePause() {
        Track t = current;
        if (t == null) {
            next();
        } else if (paused || !playing) {
            boolean atEnd = !paused && !Double.isNaN(t.seconds()) && position >= t.seconds() - 0.5;
            if (atEnd) next();
            else start(t, position, event, picked);
        } else {
            // Paused first, so other music stays held the whole time.
            paused = true;
            playing = false;
            stopAll();
        }
    }

    public void seek(double seconds) {
        Track t = current;
        if (t == null) return;
        double s = Math.max(0, Double.isNaN(t.seconds()) ? seconds : Math.min(seconds, t.seconds() - 0.5));
        if (paused) {
            position = s;
        } else {
            start(t, s, event, picked);
        }
    }

    /** The queue first, then the list's next song, then the game's next song, skipping the break. */
    public void next() {
        // With a song to blend into, the transition plays from the coming bar instead of a cut.
        if (CueConfig.INSTANCE.skipWithTransition && !paused && ours != null && automixNext != null && previewTo == null
                && CueStream.skipWithTransition()) {
            skippedByBlend = current;
            return;
        }
        cutToNext();
    }

    /** The next song now, without a transition. */
    private void cutToNext() {
        skippedByBlend = null;
        dropMissing();
        order.setRepeat(repeat());
        Track n = order.next();
        // Next again while blending into a song: past it, not back to its start.
        Track incoming = CueStream.blendingInto();
        if (n != null && n.equals(incoming)) n = order.next();
        if (n != null) {
            start(n, 0, TrackSoundInstance.GAME, true);
        } else if (upcoming() != null) {
            playUpcoming();
        } else {
            // No music from the game here: carry on through all the mode's music, still leaving out disliked songs.
            List<Track> all = radioPool().stream()
                    .filter(t -> t.equals(current) || !CueConfig.INSTANCE.isDisliked(t.location().toString())).toList();
            if (!all.isEmpty()) play(ALL, all, (all.indexOf(current) + 1) % all.size());
        }
    }

    /** Plays the game's next song now, the way the game would: with the "Now playing" toast. */
    public void playUpcoming() {
        Minecraft mc = Minecraft.getInstance();
        Music music = mc.getSituationalMusic();
        if (music != null) mc.getMusicManager().startPlaying(music);
    }

    /** Back to the start of the song, or within the first 3 seconds the list's previous song. Never the queue. */
    public void previous() {
        Track p = current != null && position > 3 ? null : order.previous();
        if (p != null) start(p, 0, TrackSoundInstance.GAME, true);
        else seek(0);
    }

    // Queue.

    public List<Track> queue() {
        return order.queue();
    }

    /** Adds to the queue. With nothing playing or paused, it plays at once. */
    public void enqueue(Track track) {
        if (!Modes.allows(track)) return;
        order.enqueue(track);
        startIfIdle();
    }

    /** Adds every song of {@code tracks} to the queue, in order. */
    public void enqueueAll(List<Track> tracks) {
        order.enqueueAll(Modes.only(tracks));
        startIfIdle();
    }

    public void unqueue(int index) {
        order.unqueue(index);
    }

    public void clearQueue() {
        order.clearQueue();
    }

    /** The key of the list playing through, or null. */
    public Object playingList() {
        return order.listKey();
    }

    /** Up to {@code n} songs the list plays after the queue, in order. */
    public List<Track> upNext(int n) {
        return order.upNext(n);
    }

    /** Plays the list's song {@code k} places on from the one playing now, skipping those before it. */
    public void playUpNext(int k) {
        Track t = order.jump(k);
        if (t != null) start(t, 0, TrackSoundInstance.GAME, true);
    }

    /** Moves the list's song {@code from} places along under Next from so it ends up {@code to} places along. */
    public void moveUpNext(int from, int to) {
        order.moveUpNext(from, to);
    }

    /** Moves the queued song at {@code from} so it ends up at {@code to}. */
    public void moveQueued(int from, int to) {
        order.moveQueued(from, to);
    }

    public void playQueued(int index) {
        Track t = order.playQueued(index);
        if (t != null) start(t, 0, TrackSoundInstance.GAME, true);
    }

    /** Repeat as it applies now: off while Radio plays, since Radio never ends. */
    public PlayOrder.Repeat repeat() {
        return isPlayingList(RADIO) ? PlayOrder.Repeat.OFF : CueConfig.INSTANCE.repeatMode();
    }

    /** The list's next song, while a list plays through and the queue is empty. On repeat one, the song that's playing. */
    public Track listNext() {
        if (repeat() == PlayOrder.Repeat.ONE && current != null) return current;
        order.setRepeat(repeat());
        return order.listNext();
    }

    /** Plays the queue's head if nothing is playing, starting or paused. Game songs count as playing. */
    private void startIfIdle() {
        if (holdsMusic() || !waitingForGame()) return;
        Track n = order.next();
        if (n != null) start(n, 0, TrackSoundInstance.GAME, true);
    }

    // The game's music.

    /** The song the game will play next, after the queue. Null if there's no music for where the player is. */
    public Track upcoming() {
        return planned;
    }

    /**
     * Ticks until the game's next song, counting from now when nothing is
     * playing, or from the end of the current song (and the queue) otherwise.
     */
    public int ticksUntilUpcoming() {
        MusicManagerAccessor music = (MusicManagerAccessor) Minecraft.getInstance().getMusicManager();
        if (music.cuemymusic$currentMusic() == null && !paused) return music.cuemymusic$nextSongDelay();
        return plannedBreak;
    }

    /** True while nothing is playing and the game is counting down to its next song. */
    public boolean waitingForGame() {
        return !paused && ((MusicManagerAccessor) Minecraft.getInstance().getMusicManager()).cuemymusic$currentMusic() == null;
    }

    /**
     * The break the game takes after a song: the one already planned, so the queue was right about it.
     * If the music changed this tick, before it was planned for, the game's own roll is used.
     */
    public int takeBreak(Music music, MusicManager.MusicFrequency frequency, IntSupplier roll) {
        if (!music.equals(plannedFor) || frequency != plannedFrequency) {
            return songGap() >= 0 ? songGap() * 20 : roll.getAsInt();
        }
        int ticks = plannedBreak;
        plannedBreak = rollBreak(plannedFor, plannedFrequency);
        return ticks;
    }

    /** Plays the game's next song for {@code music}, in place of the game. Null if there's nothing to play. */
    public SoundEngine.PlayResult playForGame(Music music) {
        plan();
        Sound sound = music.equals(plannedFor) ? plannedSound : pick(music);
        if (sound == null) return null;
        plannedFor = null;
        // The game took over, so the list is done; anything queued plays after the game's song.
        order.endList();
        Identifier event = music.sound().value().location();
        Track t = track(sound);
        SoundEngine.PlayResult result;
        if (t != null) {
            result = start(t, new TrackSoundInstance(sound, 0, event), false);
        } else {
            // The game picked silence: it "plays" it, fails to start, and waits for the next break.
            TrackSoundInstance silence = new TrackSoundInstance(sound, 0, event);
            Minecraft mc = Minecraft.getInstance();
            ((MusicManagerAccessor) mc.getMusicManager()).cuemymusic$setCurrentMusic(silence);
            result = mc.getSoundManager().play(silence);
        }
        plan();
        return result;
    }

    /**
     * The time between songs changed in Settings: the break is rolled again,
     * and a wait already longer than the new time is cut to it.
     */
    public void songGapChanged() {
        replan();
        int gap = songGap();
        MusicManagerAccessor music = (MusicManagerAccessor) Minecraft.getInstance().getMusicManager();
        if (gap >= 0 && waitingForGame() && music.cuemymusic$nextSongDelay() > gap * 20) music.cuemymusic$setNextSongDelay(gap * 20);
    }

    /**
     * The time between songs in seconds, or -1 for the game's Frequency. It's a
     * Vanilla + FX setting; Add-ons has no breaks, one song mixing into the next,
     * and with no add-on songs yet it looks again every 10 seconds.
     */
    private static int songGap() {
        return switch (Modes.current()) {
            case "fx" -> CueConfig.INSTANCE.songGap;
            case "addons" -> MusicLibrary.addOnMusic().isEmpty() ? 10 : 0;
            default -> -1;
        };
    }

    /** Picks the game's next song again, as when the mode changes where songs come from. */
    public void replan() {
        plannedFor = null;
        plannedSound = null;
        planned = null;
    }

    /** The game stopped the current track: a dimension change, or music that replaces it. */
    public void stoppedByGame() {
        synchronized (this) {
            activeStream = -1;
            playing = false;
        }
        ours = null;
    }

    /**
     * In place of the game's stop of every sound when the level changes (joining
     * a world, a dimension change, or leaving): every other sound stops, but the
     * song plays on without a break, never restarted. Changing dimension with
     * pause on travel set, it fades out (if a portal hasn't already faded it)
     * and waits for the new level, then fades back in.
     */
    public void levelChanged(boolean entering, Runnable stopAll) {
        boolean travel = entering && inWorld;
        inWorld = entering;
        TrackSoundInstance o = ours;
        if (o == null || current == null) {
            stopAll.run();
            return;
        }
        stopAllBut(o);
        // Every song plays through loading screens, the game's picks too: servers send you through
        // them all the time. Once it ends, the game picks the next from where you are now.
        if (travel && CueConfig.INSTANCE.pauseOnTravel && !paused) {
            travelOutStart = System.nanoTime();
            travelOutFrom = travelGain;
        }
    }

    /**
     * A resource pack reload is about to restart the sound engine. With a song
     * playing, it's done without the restart instead: every
     * other sound stops and the loaded sounds are dropped, as the restart
     * would, but the song plays on. Returns false, changing nothing, with no song playing.
     */
    public boolean playThroughReload(SoundEngine engine) {
        TrackSoundInstance o = ours;
        if (o == null || current == null) return false;
        stopAllBut(o);
        ((SoundEngineAccessor) engine).cuemymusic$soundBuffers().clear();
        // The game's next song was drawn from the old sound list.
        replan();
        return true;
    }

    /** The song to pick up again once the sound engine has restarted. */
    private boolean resumeAfterRestart;

    /**
     * The sound engine is about to restart, as when the audio device changes. That closes the song's
     * stream, which isn't the song ending: it carries on from the same place on the new device.
     */
    public void engineRestarting() {
        resumeAfterRestart = ours != null && current != null && !paused;
        synchronized (this) {
            activeStream = -1;
        }
    }

    /** The sound engine has restarted. */
    public void engineRestarted() {
        if (!resumeAfterRestart) return;
        resumeAfterRestart = false;
        start(current, position, event, picked);
    }

    /** Stops every sound but {@code keep}, and drops sounds waiting to start, as the game's stop of everything would. */
    private static void stopAllBut(SoundInstance keep) {
        SoundEngine engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).cuemymusic$soundEngine();
        SoundEngineAccessor sounds = (SoundEngineAccessor) engine;
        for (SoundInstance s : new java.util.ArrayList<>(sounds.cuemymusic$playing().keySet())) {
            if (s != keep) engine.stop(s);
        }
        sounds.cuemymusic$delayed().clear();
        sounds.cuemymusic$queuedTicking().removeIf(s -> s != keep);
    }

    /**
     * The song's volume through travel, with pause on travel set: it fades
     * out as a nether portal's swirl builds (silent as it takes you), and
     * back up if you step out. Once the level changes it finishes fading,
     * then pauses until the new level is on screen.
     */
    private void fadeForTravel(long now) {
        float gain = 1;
        Minecraft mc = Minecraft.getInstance();
        if (CueConfig.INSTANCE.pauseOnTravel && mc.player != null && travelOutStart == 0) {
            gain = 1 - Math.clamp(mc.player.portalEffectIntensity, 0, 1);
        }
        if (travelOutStart != 0) {
            double done = (now - travelOutStart) / 1e9 / TRAVEL_OUT_SECONDS;
            gain = (float) (travelOutFrom * Math.max(0, 1 - done));
            if (done >= 1 || travelOutFrom <= 0.01f) {
                travelOutStart = 0;
                gain = 1;
                if (ours != null && current != null && !paused) {
                    paused = true;
                    playing = false;
                    stopAll();
                    travelHold = true;
                }
            }
        }
        travelGain = gain;
        TrackSoundInstance o = ours;
        if (o != null) o.setFade(gain);
    }

    /**
     * The new level is on screen and can be heard: the player's chunk has
     * loaded and the loading screen has closed. The game marks the player loaded a moment
     * before the loading screen goes, so that alone resumes too early.
     */
    private static boolean levelReady() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        return connection != null && connection.hasClientLoaded() && mc.level != null && mc.player != null
                && !(mc.gui.screen() instanceof LevelLoadingScreen)
                // In the background with Pause in background on, it waits for the window instead of starting and pausing at once.
                && (mc.isWindowActive() || !CueConfig.INSTANCE.pauseOnLostFocus);
    }

    /** Picks the next song and break again when the game's music or the time between songs changes. */
    private void plan() {
        Minecraft mc = Minecraft.getInstance();
        Music music = mc.getSituationalMusic();
        MusicManager.MusicFrequency frequency = mc.options.musicFrequency().get();
        if (music == null) {
            plannedFor = null;
            plannedSound = null;
            planned = null;
            return;
        }
        // Also picked again while there's nothing to pick from (sounds still loading).
        if (music.equals(plannedFor) && frequency == plannedFrequency && plannedSound != null) return;
        plannedFor = music;
        plannedFrequency = frequency;
        plannedSound = pick(music);
        planned = track(plannedSound);
        plannedBreak = rollBreak(music, frequency);
    }

    /**
     * The game's own roll: up to 20 minutes on the default setting, 10 on frequent, 5 seconds on constant.
     * With a time between songs set in Settings, that instead, the same every time.
     */
    private int rollBreak(Music music, MusicManager.MusicFrequency frequency) {
        int gap = songGap();
        if (gap >= 0) return gap * 20;
        return Math.min(((MusicFrequencyInvoker) (Object) frequency).cuemymusic$nextSongDelay(music, random), music.maxDelay());
    }

    /**
     * One draw from the music's sounds, weighted as in sounds.json, the same draw the game makes when it starts a song.
     * Disliked songs are drawn again, which keeps the others' weights as they were. If every draw is disliked,
     * it's silence, so the game waits for the next break instead.
     */
    private Sound pick(Music music) {
        CueConfig cfg = CueConfig.INSTANCE;
        // Add-ons mode: the game still says when music plays; the song is one of the add-ons'.
        if (Modes.addOnsActive()) {
            List<Track> songs = MusicLibrary.addOnMusic().stream()
                    .filter(t -> !CueConfig.INSTANCE.isDisliked(t.location().toString())).toList();
            for (int i = 0; i < 8 && !songs.isEmpty(); i++) {
                Sound s = MusicLibrary.sound(songs.get(random.nextInt(songs.size())).location());
                if (s != null) return s;
            }
            // Nothing of the add-ons' to play: silence, never one of the game's songs, which Add-ons won't start.
            return SoundManager.EMPTY_SOUND;
        }
        Identifier pool = music.sound().value().location();
        WeighedSoundEvents events = Minecraft.getInstance().getSoundManager().getSoundEvent(pool);
        if (events == null) return null;
        // Your changes to the pool: songs taken out are drawn again, and songs put in come up as often as the game's own.
        TrackPools.Edit edit = cfg.poolEdit(pool.toString());
        if (!edit.isEmpty()) {
            int left = (int) MusicLibrary.gameMusic().stream()
                    .filter(t -> MusicLibrary.inPool(t, pool) && Modes.allows(t) && !edit.removed().contains(t.location().toString())
                            && !cfg.isDisliked(t.location().toString()))
                    .count();
            List<Track> added = edit.added().stream().map(Identifier::tryParse).filter(java.util.Objects::nonNull).map(MusicLibrary::get)
                    .filter(t -> t != null && Modes.allows(t) && !cfg.isDisliked(t.location().toString())).toList();
            int i = TrackPools.choose(left, added.size(), random.nextDouble());
            if (i >= 0) {
                Sound s = MusicLibrary.sound(added.get(i).location());
                if (s != null) return s;
            }
            if (left == 0) return SoundManager.EMPTY_SOUND;
        }
        for (int i = 0; i < 64; i++) {
            Sound s = events.getSound(random);
            Track t = track(s);
            if (t == null) return s;
            String id = t.location().toString();
            // A pack's song in the game's pool is an add-on song, which Vanilla + FX doesn't play: drawn again.
            if (Modes.allows(t) && !cfg.isDisliked(id) && !edit.removed().contains(id)) return s;
        }
        return SoundManager.EMPTY_SOUND;
    }

    /**
     * Dislikes the song, or takes the dislike away. A disliked song the game had
     * planned is swapped for another, keeping the same break, and one the game
     * is playing now is skipped. Returns whether it's disliked now.
     */
    public boolean toggleDisliked(Track track) {
        CueConfig.INSTANCE.toggleDisliked(track.location().toString());
        boolean disliked = CueConfig.INSTANCE.isDisliked(track.location().toString());
        if (disliked) {
            if (track.equals(planned) && plannedFor != null) {
                plannedSound = pick(plannedFor);
                planned = track(plannedSound);
            }
            if (track.equals(current) && ours != null && !picked) next();
        }
        return disliked;
    }

    private static Track track(Sound sound) {
        if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND
                || sound.getType() != Sound.Type.FILE) return null;
        return MusicLibrary.get(sound.getLocation());
    }

    private SoundEngine.PlayResult start(Track track, double seconds, Identifier event, boolean picked) {
        return start(track, TrackSoundInstance.of(track.location(), seconds, event), picked);
    }

    private SoundEngine.PlayResult start(Track track, TrackSoundInstance o, boolean picked) {
        // The hard line between the modes: nothing from the other mode's music starts, however it was asked for.
        if (!Modes.allows(track)) return SoundEngine.PlayResult.NOT_STARTED;
        // For the stats: moving on to another song may be a skip. The same song again (resume, seek, travel,
        // or clicking it again) carries on; only playing it again after it ended is a new play.
        boolean resuming = track.equals(current) && !replay;
        replay = false;
        if (!resuming) {
            if (current != null) ListeningStats.INSTANCE.skipped(current.location().toString());
            ListeningStats.INSTANCE.started(track.location().toString());
        }
        // The new track takes the hold before anything is stopped, so no other music can slip in between.
        TrackSoundInstance old = ours;
        Identifier event = o.getIdentifier();
        synchronized (this) {
            activeStream = -1;
            current = track;
            position = o.startSeconds();
            paused = false;
            playing = false;
            ended = false;
            lastNanos = System.nanoTime();
        }
        ours = o;
        handover = null;
        travelHold = false;
        this.event = event;
        this.picked = picked;
        oursStarted = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        MusicManagerAccessor music = (MusicManagerAccessor) mc.getMusicManager();
        SoundInstance before = music.cuemymusic$currentMusic();
        if (before != null && before != old) mc.getSoundManager().stop(before);
        if (old != null) mc.getSoundManager().stop(old);
        // The game sees the track as its own music, so it waits for it to end before planning the next song.
        music.cuemymusic$setCurrentMusic(o);
        music.cuemymusic$setNextSongDelay(Integer.MAX_VALUE);
        return mc.getSoundManager().play(o);
    }

    /**
     * Vanilla music is being turned on: stops the player's song and hands the
     * music back to the game, which plans its next song as it would without
     * the mod, after a short break.
     */
    public void stopForVanilla() {
        if (current != null && playing) ListeningStats.INSTANCE.skipped(current.location().toString());
        stopAll();
        synchronized (this) {
            playing = false;
        }
        paused = false;
        travelHold = false;
        focusPaused = false;
        automixNext = null;
        ((MusicManagerAccessor) Minecraft.getInstance().getMusicManager()).cuemymusic$setNextSongDelay(100);
    }

    /**
     * The mode changed: songs from the other mode's music leave the queue
     * and the list playing, and one playing now stops, so the game plays
     * the new mode's music from here.
     */
    void modeChanged() {
        order.retain(Modes::allows);
        Track t = current;
        if (t == null || Modes.allows(t)) return;
        // Paused too: a paused song from the other mode would hold the game's music back for good.
        stopForVanilla();
        synchronized (this) {
            current = null;
            position = 0;
        }
    }

    /** Songs whose files are gone (a download deleted) leave the queue and the list, so they aren't tried in turn. */
    private void dropMissing() {
        java.util.Set<Identifier> there = new java.util.HashSet<>();
        for (Track t : MusicLibrary.all()) there.add(t.location());
        order.retain(t -> there.contains(t.location()));
    }

    /** Stops whatever music is playing. The stream's close isn't taken as the song ending. */
    private void stopAll() {
        synchronized (this) {
            activeStream = -1;
        }
        Minecraft mc = Minecraft.getInstance();
        TrackSoundInstance o = ours;
        ours = null;
        handover = null;
        MusicManagerAccessor music = (MusicManagerAccessor) mc.getMusicManager();
        SoundInstance current = music.cuemymusic$currentMusic();
        if (current != null) mc.getSoundManager().stop(current);
        music.cuemymusic$setCurrentMusic(null);
        mc.gui.toastManager().hideNowPlayingToast();
        if (o != null) mc.getSoundManager().stop(o);
    }
}
