package mix.cuemymusic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays the audio it's sent on stdin, in a process of its own. A recording of
 * the game's window takes only the game process's audio, so songs played from
 * here are heard but not recorded.
 *
 * <p>stdin, big-endian:
 * <ul>
 * <li>{@code 'S'} rate (int), channels (int): the format of the audio after it.
 * <li>{@code 'D'} length (int), bytes: 16-bit little-endian PCM.
 * <li>{@code 'X'}: drop everything not yet played.
 * <li>{@code 'V'} volume (float): applied to audio as it's played.
 * </ul>
 * It exits when stdin closes, which happens however the game exits.
 */
public final class OutsidePlayer {
    public static final int FORMAT = 'S';
    public static final int DATA = 'D';
    public static final int FLUSH = 'X';
    public static final int VOLUME = 'V';

    private record Chunk(long generation, AudioFormat format, byte[] data) {
    }

    private static final LinkedBlockingQueue<Chunk> QUEUE = new LinkedBlockingQueue<>();
    /** Goes up on every flush; audio from before it isn't played. */
    private static volatile long generation;
    private static volatile float volume = 1;
    private static volatile SourceDataLine line;

    private OutsidePlayer() {
    }

    public static void main(String[] args) throws IOException {
        Thread player = new Thread(OutsidePlayer::play, "outside-player");
        player.setDaemon(true);
        player.start();
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 1 << 16));
        AudioFormat format = null;
        try {
            while (true) {
                int op = in.read();
                if (op < 0) break;
                switch (op) {
                    case FORMAT -> format = new AudioFormat(in.readInt(), 16, in.readInt(), true, false);
                    case DATA -> {
                        byte[] data = new byte[in.readInt()];
                        in.readFully(data);
                        if (format != null) QUEUE.add(new Chunk(generation, format, data));
                    }
                    case FLUSH -> {
                        generation++;
                        QUEUE.clear();
                        SourceDataLine l = line;
                        if (l != null) l.flush();
                    }
                    case VOLUME -> volume = in.readFloat();
                    default -> throw new IOException("unknown command " + op);
                }
            }
        } catch (EOFException ignored) {
            // The game is gone.
        }
        System.exit(0);
    }

    private static void play() {
        AudioFormat open = null;
        try {
            while (true) {
                Chunk c = QUEUE.take();
                if (c.generation() != generation) continue;
                if (open == null || !c.format().matches(open)) {
                    if (line != null) line.close();
                    SourceDataLine l = AudioSystem.getSourceDataLine(c.format());
                    // A tenth of a second: short enough that a flush or a volume change is heard at once.
                    l.open(c.format(), (int) (c.format().getFrameRate() / 10) * c.format().getFrameSize());
                    l.start();
                    line = l;
                    open = c.format();
                }
                byte[] data = c.data();
                scale(data, volume);
                int step = open.getFrameSize() * 512;
                for (int i = 0; i < data.length && c.generation() == generation; i += step) {
                    line.write(data, i, Math.min(step, data.length - i));
                }
            }
        } catch (InterruptedException | LineUnavailableException | IllegalArgumentException e) {
            System.err.println("outside player stopped: " + e);
            System.exit(1);
        }
    }

    private static void scale(byte[] pcm, float gain) {
        if (gain >= 0.999f) return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            v = Math.round(v * gain);
            pcm[i] = (byte) v;
            pcm[i + 1] = (byte) (v >> 8);
        }
    }
}
