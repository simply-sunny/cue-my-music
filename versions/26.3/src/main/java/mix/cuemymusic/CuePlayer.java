package mix.cuemymusic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
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
 * through dimension changes and menus. Songs added to the queue play as soon
 * as the current one ends; after that the game's schedule takes over again.
 */
public final class CuePlayer {
    public static final CuePlayer INSTANCE = new CuePlayer();
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
    private List<Track> context = List.of();
    private final List<Track> queue = new ArrayList<>();
    private long lastNanos;

    // The game's next song, planned ahead.
    private final RandomSource random = RandomSource.create();
    private Music plannedFor;
    private MusicManager.MusicFrequency plannedFrequency;
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

    void streamStarted(long id, Identifier location, double offset, SoundInstance instance) {
        // Music from somewhere else that was already loading when the player took over: it gets stopped, don't show it.
        if (holdsMusic() && !isOurs(instance)) return;
        synchronized (this) {
            activeStream = id;
            current = MusicLibrary.get(location);
            position = offset;
            playing = true;
            paused = false;
            ended = false;
            lastNanos = System.nanoTime();
        }
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
                position += (now - lastNanos) / 1e9 * CueConfig.INSTANCE.tempo();
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
            if (!queue.isEmpty()) start(queue.removeFirst(), 0, TrackSoundInstance.GAME, true);
            return;
        }
        TrackSoundInstance o = ours;
        if (o != null && !playing && !paused && now - oursStarted > START_TIMEOUT_NANOS
                && !Minecraft.getInstance().getSoundManager().isActive(o)) {
            ours = null;
        }
    }

    /** Plays {@code track} from {@code seconds}; previous then walks {@code list}. */
    public void play(Track track, double seconds, List<Track> list) {
        this.context = List.copyOf(list);
        start(track, seconds, TrackSoundInstance.GAME, true);
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

    /** The queue first, then the game's next song, skipping the break. */
    public void next() {
        if (!queue.isEmpty()) {
            start(queue.removeFirst(), 0, TrackSoundInstance.GAME, true);
            return;
        }
        if (upcoming() != null) {
            playUpcoming();
        } else {
            step(1);
        }
    }

    /** Plays the game's next song now, the way the game would: with the "Now playing" toast. */
    public void playUpcoming() {
        Minecraft mc = Minecraft.getInstance();
        Music music = mc.getSituationalMusic();
        if (music != null) mc.getMusicManager().startPlaying(music);
    }

    public void previous() {
        if (current != null && position > 3) {
            seek(0);
        } else {
            step(-1);
        }
    }

    // Queue.

    public List<Track> queue() {
        return List.copyOf(queue);
    }

    public void enqueue(Track track) {
        queue.add(track);
    }

    public void unqueue(int index) {
        if (index >= 0 && index < queue.size()) queue.remove(index);
    }

    /** Moves the queued song at {@code from} so it ends up at {@code to}. */
    public void moveQueued(int from, int to) {
        if (from < 0 || from >= queue.size()) return;
        Track t = queue.remove(from);
        queue.add(Math.clamp(to, 0, queue.size()), t);
    }

    public void playQueued(int index) {
        if (index >= 0 && index < queue.size()) start(queue.remove(index), 0, TrackSoundInstance.GAME, true);
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

    /** The break the game takes after a song: the one already planned, so the queue was right about it. */
    public int takeBreak() {
        int ticks = plannedBreak;
        if (plannedFor != null) plannedBreak = rollBreak(plannedFor, plannedFrequency);
        return ticks;
    }

    /** Plays the game's next song for {@code music}, in place of the game. Null if there's nothing to play. */
    public SoundEngine.PlayResult playForGame(Music music) {
        plan();
        Track t = music.equals(plannedFor) ? planned : pick(music);
        if (t == null) return null;
        plannedFor = null;
        SoundEngine.PlayResult result = start(t, 0, music.sound().value().location(), false);
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

    /** Picks the next song and break again when the game's music or the time between songs changes. */
    private void plan() {
        Minecraft mc = Minecraft.getInstance();
        Music music = mc.getSituationalMusic();
        MusicManager.MusicFrequency frequency = mc.options.musicFrequency().get();
        if (music == null) {
            plannedFor = null;
            planned = null;
            return;
        }
        // Also picked again when there was nothing to pick (sounds still loading) or the pick went away with a pack.
        if (music.equals(plannedFor) && frequency == plannedFrequency && planned != null && MusicLibrary.has(planned.location())) return;
        plannedFor = music;
        plannedFrequency = frequency;
        planned = pick(music);
        plannedBreak = rollBreak(music, frequency);
    }

    /** The game's own roll: up to 20 minutes on the default setting, 10 on frequent, 5 seconds on constant. */
    private int rollBreak(Music music, MusicManager.MusicFrequency frequency) {
        return Math.min(((MusicFrequencyInvoker) (Object) frequency).cuemymusic$nextSongDelay(music, random), music.maxDelay());
    }

    /** A song for {@code music}, the way the game picks one, skipping missing files and the song that's on. */
    private Track pick(Music music) {
        WeighedSoundEvents events = Minecraft.getInstance().getSoundManager().getSoundEvent(music.sound().value().location());
        if (events == null) return null;
        Track fallback = null;
        for (int i = 0; i < 8; i++) {
            Sound sound = events.getSound(random);
            if (sound == null || sound.getType() != Sound.Type.FILE || !MusicLibrary.has(sound.getLocation())) continue;
            Track t = MusicLibrary.get(sound.getLocation());
            if (!t.equals(current)) return t;
            fallback = t;
        }
        return fallback;
    }

    private void step(int direction) {
        List<Track> list = context.isEmpty() ? MusicLibrary.all() : context;
        if (list.isEmpty()) return;
        int i = current == null ? -1 : list.indexOf(current);
        int n = Math.floorMod(i + direction, list.size());
        if (i < 0 && direction < 0) n = list.size() - 1;
        start(list.get(n), 0, TrackSoundInstance.GAME, true);
    }

    private SoundEngine.PlayResult start(Track track, double seconds, Identifier event, boolean picked) {
        // The new track takes the hold before anything is stopped, so no other music can slip in between.
        TrackSoundInstance old = ours;
        TrackSoundInstance o = new TrackSoundInstance(track.location(), seconds, event);
        synchronized (this) {
            activeStream = -1;
            current = track;
            position = seconds;
            paused = false;
            playing = false;
            ended = false;
            lastNanos = System.nanoTime();
        }
        ours = o;
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
