package mix.cuemymusic;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import mix.cuemymusic.library.MusicLibrary;
import mix.cuemymusic.library.Track;

/**
 * What's playing and where, for the player screen, and the play controls.
 *
 * <p>Every music stream reports itself here when it starts and closes, whether
 * the game or the player started it. Pause, seek and resume all restart the
 * track at a position: streams can't rewind, but skipping ahead is fast.
 * While the player has a track playing or paused, the game's own music
 * manager is held (see {@code MusicManagerMixin}).
 */
public final class CuePlayer {
    public static final CuePlayer INSTANCE = new CuePlayer();
    private static final AtomicLong STREAM_IDS = new AtomicLong();

    private volatile Track current;
    private volatile double position;
    private volatile boolean playing;
    private volatile boolean paused;
    private volatile long activeStream = -1;
    private TrackSoundInstance ours;
    private List<Track> queue = List.of();
    private long lastNanos;

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

    /** True while the game's music manager should stay quiet. */
    public boolean holdsMusic() {
        if (paused) return true;
        TrackSoundInstance o = ours;
        return o != null && Minecraft.getInstance().getSoundManager().isActive(o);
    }

    // Called from the stream-loading thread.

    static long nextStreamId() {
        return STREAM_IDS.incrementAndGet();
    }

    void streamStarted(long id, Identifier location, double offset) {
        synchronized (this) {
            activeStream = id;
            current = MusicLibrary.get(location);
            position = offset;
            playing = true;
            paused = false;
            lastNanos = System.nanoTime();
        }
    }

    void streamClosed(long id) {
        synchronized (this) {
            if (id == activeStream) playing = false;
        }
    }

    // Client thread.

    public void tick() {
        long now = System.nanoTime();
        synchronized (this) {
            if (playing && !paused) {
                position += (now - lastNanos) / 1e9 * CueConfig.INSTANCE.tempo();
                Track t = current;
                if (t != null && !Double.isNaN(t.seconds())) position = Math.min(position, t.seconds());
            }
            lastNanos = now;
        }
        if (ours != null && !paused && !Minecraft.getInstance().getSoundManager().isActive(ours) && !playing) {
            ours = null;
        }
    }

    /** Plays {@code track} from {@code seconds}; next/previous then walk {@code queue}. */
    public void play(Track track, double seconds, List<Track> queue) {
        this.queue = queue;
        start(track, seconds);
    }

    public void togglePause() {
        Track t = current;
        if (t == null) {
            List<Track> all = MusicLibrary.all();
            if (!all.isEmpty()) play(all.getFirst(), 0, all);
        } else if (paused || !playing) {
            boolean ended = !paused && !Double.isNaN(t.seconds()) && position >= t.seconds() - 0.5;
            start(t, ended ? 0 : position);
        } else {
            stopAll();
            paused = true;
            playing = false;
        }
    }

    public void seek(double seconds) {
        Track t = current;
        if (t == null) return;
        double s = Math.max(0, Double.isNaN(t.seconds()) ? seconds : Math.min(seconds, t.seconds() - 0.5));
        if (paused) {
            position = s;
        } else {
            start(t, s);
        }
    }

    public void next() {
        step(1);
    }

    public void previous() {
        if (current != null && position > 3) {
            seek(0);
        } else {
            step(-1);
        }
    }

    private void step(int direction) {
        List<Track> q = queue.isEmpty() ? MusicLibrary.all() : queue;
        if (q.isEmpty()) return;
        int i = current == null ? -1 : q.indexOf(current);
        int n = Math.floorMod(i + direction, q.size());
        if (i < 0 && direction < 0) n = q.size() - 1;
        start(q.get(n), 0);
    }

    private void start(Track track, double seconds) {
        stopAll();
        synchronized (this) {
            current = track;
            position = seconds;
            paused = false;
            lastNanos = System.nanoTime();
        }
        ours = new TrackSoundInstance(track.location(), seconds);
        Minecraft.getInstance().getSoundManager().play(ours);
    }

    private void stopAll() {
        Minecraft mc = Minecraft.getInstance();
        mc.getMusicManager().stopPlaying();
        if (ours != null) mc.getSoundManager().stop(ours);
        ours = null;
    }
}
