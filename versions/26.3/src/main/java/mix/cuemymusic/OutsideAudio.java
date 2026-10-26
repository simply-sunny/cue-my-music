package mix.cuemymusic;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The game's side of {@link OutsidePlayer}: starts it the first time it's
 * needed and sends it audio. One stream owns it at a time; audio from any
 * other stream is ignored. Writes go through one background thread, so the
 * sound thread never waits on the pipe.
 */
final class OutsideAudio {
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cuemymusic-outside");
        t.setDaemon(true);
        return t;
    });

    private static Process process;
    private static DataOutputStream out;
    private static boolean failed;
    private static long owner = -1;
    private static float volume = -1;

    private OutsideAudio() {
    }

    /** Makes stream {@code id} the one playing, dropping what's left of any other. False if the player can't run. */
    static synchronized boolean begin(long id, int rate, int channels) {
        if (!running()) return false;
        owner = id;
        send(o -> {
            o.write(OutsidePlayer.FLUSH);
            o.write(OutsidePlayer.FORMAT);
            o.writeInt(rate);
            o.writeInt(channels);
        });
        return true;
    }

    /** Sends {@code count} interleaved samples from stream {@code id}. */
    static synchronized void play(long id, float[] samples, int count) {
        if (id != owner) return;
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int v = Math.round(Math.clamp(samples[i], -1f, 1f) * 32767);
            pcm[i * 2] = (byte) v;
            pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        send(o -> {
            o.write(OutsidePlayer.DATA);
            o.writeInt(pcm.length);
            o.write(pcm);
        });
    }

    /** Stream {@code id} stopped: whatever it sent that hasn't played yet is dropped. */
    static synchronized void end(long id) {
        if (id != owner) return;
        owner = -1;
        send(o -> o.write(OutsidePlayer.FLUSH));
    }

    static synchronized void volume(float v) {
        if (process == null || v == volume) return;
        volume = v;
        send(o -> {
            o.write(OutsidePlayer.VOLUME);
            o.writeFloat(v);
        });
    }

    static synchronized void shutdown() {
        if (process != null) process.destroy();
        process = null;
    }

    private interface Write {
        void to(DataOutputStream o) throws IOException;
    }

    private static void send(Write w) {
        DataOutputStream o = out;
        if (o == null) return;
        WRITER.execute(() -> {
            try {
                w.to(o);
                o.flush();
            } catch (IOException e) {
                synchronized (OutsideAudio.class) {
                    if (out == o) {
                        LOG.warn("The outside player stopped: {}", e.getMessage());
                        out = null;
                        process = null;
                    }
                }
            }
        });
    }

    /** Starts the player if it isn't running. After a failed start it doesn't try again. */
    private static boolean running() {
        if (process != null && process.isAlive() && out != null) return true;
        if (failed) return false;
        try {
            String java = ProcessHandle.current().info().command().orElse(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString());
            String classpath = FabricLoader.getInstance().getModContainer("cuemymusic").orElseThrow().getOrigin().getPaths()
                    .stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
            List<String> command = new ArrayList<>(List.of(java, "-Xmx48m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                    "-Djava.awt.headless=true", "-cp", classpath, OutsidePlayer.class.getName()));
            Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("cuemymusic-outside.log");
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.to(log.toFile()))
                    .start();
            out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream(), 1 << 16));
            volume = -1;
            LOG.info("Started the outside player, pid {}", process.pid());
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Couldn't start the outside player; add-on songs play in the game", e);
            failed = true;
            process = null;
            out = null;
            return false;
        }
    }
}
