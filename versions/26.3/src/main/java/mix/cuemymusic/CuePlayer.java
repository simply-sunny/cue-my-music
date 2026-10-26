package mix.cuemymusic;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.util.RandomSource;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;
import mix.cuemymusic.mixin.MusicFrequencyInvoker;
import mix.cuemymusic.mixin.MusicManagerAccessor;

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
    private long lastNanos;

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
        synchronized (this) {
            if (playing && !paused) {
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
        plan();
        if (finished) {
            ours = null;
            order.setRepeat(CueConfig.INSTANCE.repeatMode());
            Track n = order.finished(current);
            if (n != null) start(n, 0, TrackSoundInstance.GAME, true);
            return;
        }
        if (travelHold) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null && connection.hasClientLoaded()) {
                travelHold = false;
                if (paused && current != null) start(current, position, event, picked);
            }
        }
        TrackSoundInstance o = ours;
        if (o != null && !playing && !paused && now - oursStarted > START_TIMEOUT_NANOS
                && !Minecraft.getInstance().getSoundManager().isActive(o)) {
            ours = null;
        }
    }

    /** Plays {@code list} from {@code index} to the end, after anything queued. {@code key} names the list. */
    public void play(Object key, List<Track> list, int index) {
        start(order.play(key, list, index), 0, TrackSoundInstance.GAME, true);
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
        order.setRepeat(CueConfig.INSTANCE.repeatMode());
        Track n = order.next();
        if (n != null) {
            start(n, 0, TrackSoundInstance.GAME, true);
        } else if (upcoming() != null) {
            playUpcoming();
        } else {
            // No music from the game here: carry on through all game music.
            List<Track> all = MusicLibrary.all();
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
        order.enqueue(track);
        startIfIdle();
    }

    /** Adds every song of {@code tracks} to the queue, in order. */
    public void enqueueAll(List<Track> tracks) {
        order.enqueueAll(tracks);
        startIfIdle();
    }

    public void unqueue(int index) {
        order.unqueue(index);
    }

    /** Moves the queued song at {@code from} so it ends up at {@code to}. */
    public void moveQueued(int from, int to) {
        order.moveQueued(from, to);
    }

    public void playQueued(int index) {
        Track t = order.playQueued(index);
        if (t != null) start(t, 0, TrackSoundInstance.GAME, true);
    }

    /** The list's next song, while a list plays through and the queue is empty. On repeat one, the song that's playing. */
    public Track listNext() {
        if (CueConfig.INSTANCE.repeatMode() == PlayOrder.Repeat.ONE && current != null) return current;
        order.setRepeat(CueConfig.INSTANCE.repeatMode());
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
        if (!music.equals(plannedFor) || frequency != plannedFrequency) return roll.getAsInt();
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

    /** The game stopped the current track: a dimension change, or music that replaces it. */
    public void stoppedByGame() {
        synchronized (this) {
            activeStream = -1;
            playing = false;
        }
        ours = null;
    }

    /**
     * Runs the game's stop of every sound when the level changes: joining a world,
     * a dimension change, or leaving. A picked track carries on from the same spot,
     * or, with pause on travel set and a new level coming, pauses until it has loaded.
     */
    public void levelChanged(boolean travel, Runnable stopAll) {
        Track t = current;
        double at = position;
        boolean keep = ours != null && picked && t != null;
        // The stream's close isn't the song ending.
        if (keep) {
            synchronized (this) {
                activeStream = -1;
            }
        }
        stopAll.run();
        if (!keep) return;
        if (travel && CueConfig.INSTANCE.pauseOnTravel) {
            paused = true;
            playing = false;
            position = at;
            stopAll();
            travelHold = true;
        } else {
            start(t, at, event, true);
        }
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

    /** The game's own roll: up to 20 minutes on the default setting, 10 on frequent, 5 seconds on constant. */
    private int rollBreak(Music music, MusicManager.MusicFrequency frequency) {
        return Math.min(((MusicFrequencyInvoker) (Object) frequency).cuemymusic$nextSongDelay(music, random), music.maxDelay());
    }

    /** One draw from the music's sounds, weighted as in sounds.json, the same draw the game makes when it starts a song. */
    private Sound pick(Music music) {
        WeighedSoundEvents events = Minecraft.getInstance().getSoundManager().getSoundEvent(music.sound().value().location());
        return events == null ? null : events.getSound(random);
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

    /** Stops whatever music is playing. The stream's close isn't taken as the song ending. */
    private void stopAll() {
        synchronized (this) {
            activeStream = -1;
        }
        Minecraft mc = Minecraft.getInstance();
        TrackSoundInstance o = ours;
        ours = null;
        MusicManagerAccessor music = (MusicManagerAccessor) mc.getMusicManager();
        SoundInstance current = music.cuemymusic$currentMusic();
        if (current != null) mc.getSoundManager().stop(current);
        music.cuemymusic$setCurrentMusic(null);
        mc.gui.toastManager().hideNowPlayingToast();
        if (o != null) mc.getSoundManager().stop(o);
    }
}
