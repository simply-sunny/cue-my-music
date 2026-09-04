package com.cuemymusic.client.testing;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.client.playback.MusicDirector;
import com.cuemymusic.client.playback.PlaybackState;
import com.cuemymusic.client.ui.JukeboxLibraryScreen;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Test-only automated client driver that exercises visible playback UI and OpenAL audio.
 * Registered reflectively when {@code -Dcuemymusic.autotest=true} is set.
 * Excluded from production jar and sourcesJar.
 */
public final class AutomatedClientAudioDriver {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/autotest");

    public enum State {
        WAIT_READY,
        BASELINE,
        PLAY_A,
        WAIT_A,
        SEEK_A,
        WAIT_SEEK,
        SWITCH_B,
        WAIT_B,
        PAUSE_B,
        WAIT_PAUSE,
        RESUME_B,
        WAIT_RESUME,
        FINISH,
        FAILED
    }

    private static final Path OUTPUT_DIR = Path.of("build/client-audio-test");
    private static final Path EVENTS_FILE = OUTPUT_DIR.resolve("events.jsonl");

    private static final Map<SoundSource, Double> SNAPSHOTTED_VOLUMES = new EnumMap<>(SoundSource.class);

    private static boolean registered = false;
    private static BufferedWriter eventsWriter;
    private static State currentState = State.WAIT_READY;
    private static boolean stopped = false;

    private static long scenarioStartTimeMs = 0;
    private static long silenceStartTimeMs = 0;
    private static long stateEntryTimeMs = 0;
    private static long seekDispatchTimeMs = 0;

    private static String currentTrackId = null;
    private static Double currentPosition = null;

    private AutomatedClientAudioDriver() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            Files.createDirectories(OUTPUT_DIR);
            eventsWriter = Files.newBufferedWriter(
                    EVENTS_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize events.jsonl at " + EVENTS_FILE, e);
        }

        scenarioStartTimeMs = System.currentTimeMillis();
        stateEntryTimeMs = scenarioStartTimeMs;

        // Emit first event with PID
        long pid = ProcessHandle.current().pid();
        writeEvent(State.WAIT_READY, pid, null, null, true, null);

        ClientTickEvents.END_CLIENT_TICK.register(AutomatedClientAudioDriver::onClientTick);
        LOGGER.info("[AutoTest] Registered automated client audio driver (PID: {})", pid);
    }

    private static void onClientTick(Minecraft mc) {
        if (stopped) {
            return;
        }

        long now = System.currentTimeMillis();

        // Global 30-second timeout guard
        if (now - scenarioStartTimeMs > 30_000L) {
            fail(mc, "Global timeout (30s) exceeded in state " + currentState);
            return;
        }

        try {
            switch (currentState) {
                case WAIT_READY -> handleWaitReady(mc, now);
                case BASELINE -> handleBaseline(mc, now);
                case PLAY_A -> handlePlayA(mc, now);
                case WAIT_A -> handleWaitA(mc, now);
                case SEEK_A -> handleSeekA(mc, now);
                case WAIT_SEEK -> handleWaitSeek(mc, now);
                case SWITCH_B -> handleSwitchB(mc, now);
                case WAIT_B -> handleWaitB(mc, now);
                case PAUSE_B -> handlePauseB(mc, now);
                case WAIT_PAUSE -> handleWaitPause(mc, now);
                case RESUME_B -> handleResumeB(mc, now);
                case WAIT_RESUME -> handleWaitResume(mc, now);
                case FINISH, FAILED -> {
                    // terminal states
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AutoTest] Unexpected exception during state {}", currentState, e);
            fail(mc, "Exception in " + currentState + ": " + e.getMessage());
        }
    }

    private static void handleWaitReady(Minecraft mc, long now) {
        if (!isClientReady(mc)) {
            return;
        }

        LOGGER.info("[AutoTest] Client ready. Setting test sound profile and opening library screen.");
        snapshotVolumes(mc);
        applyTestSoundProfile(mc);
        mc.setScreenAndShow(new JukeboxLibraryScreen(null));

        silenceStartTimeMs = now;
        transition(State.BASELINE, null, null, true, null);
    }

    private static void handleBaseline(Minecraft mc, long now) {
        // Enforce at least 1.0 second of silence before playing Sweden
        if (now - silenceStartTimeMs >= 1000L) {
            transition(State.PLAY_A, "vanilla:sweden", 0.0, true, null);
        }
    }

    private static void handlePlayA(Minecraft mc, long now) {
        MusicLibrary lib = CueMyMusic.getInstance().getLibrary();
        MusicTrack trackA = lib.getTrack("vanilla:sweden").orElse(null);
        if (trackA == null) {
            fail(mc, "Track vanilla:sweden missing from library");
            return;
        }

        boolean started = MusicDirector.getInstance().previewTrack(mc, trackA);
        if (!started) {
            fail(mc, "previewTrack failed for vanilla:sweden");
            return;
        }

        LOGGER.info("[AutoTest] Started preview for vanilla:sweden");
        transition(State.WAIT_A, "vanilla:sweden", 0.0, true, null);
    }

    private static void handleWaitA(Minecraft mc, long now) {
        MusicDirector director = MusicDirector.getInstance();
        if (director.getState() != PlaybackState.PLAYING) {
            fail(mc, "Playback state changed unexpectedly while playing Sweden: " + director.getState());
            return;
        }

        // Fixed capture window for initial playback: audio captured 1-3s after Sweden starts.
        // Wait 3.5s total to ensure window is fully recorded before seek.
        if (now - stateEntryTimeMs >= 3500L) {
            if (!director.canSeek() || director.getDurationSeconds() <= 0) {
                fail(mc, "Seek capability not available after 3.5s of playback (duration: " + director.getDurationSeconds() + ")");
                return;
            }
            transition(State.SEEK_A, "vanilla:sweden", (double) director.getPositionSeconds(), true, null);
        }
    }

    private static void handleSeekA(Minecraft mc, long now) {
        if (seekDispatchTimeMs > 0) {
            checkSeekApplied(mc, now);
            return;
        }

        Screen screen = mc.gui != null ? mc.gui.screen() : null;
        if (screen == null) {
            fail(mc, "No active screen during SEEK_A");
            return;
        }

        AbstractSliderButton slider = findPlaybackSlider(screen);
        if (slider == null) {
            fail(mc, "Could not find single visible AbstractSliderButton");
            return;
        }
        if (!slider.active) {
            fail(mc, "Visible slider is not active");
            return;
        }

        MusicDirector director = MusicDirector.getInstance();
        float duration = director.getDurationSeconds();
        if (duration <= 0) {
            fail(mc, "Duration <= 0 in SEEK_A: " + duration);
            return;
        }

        // Compute x coordinate exactly per spec: slider.getX() + round(slider.getWidth() * 60 / duration)
        double x = slider.getX() + Math.round(slider.getWidth() * 60.0 / duration);
        double y = slider.getY() + slider.getHeight() / 2.0;

        LOGGER.info("[AutoTest] Dispatching real mouse click/release on slider at ({}, {}) for duration {}", x, y, duration);
        var click = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
        screen.mouseClicked(click, false);
        screen.mouseReleased(click);

        seekDispatchTimeMs = now;
        // Wait for seek to take effect before emitting seek_applied / transitioning to WAIT_SEEK
        // Transition to WAIT_SEEK occurs once runtime position reports success
        checkSeekApplied(mc, now);
    }

    private static void checkSeekApplied(Minecraft mc, long now) {
        MusicDirector director = MusicDirector.getInstance();
        float pos = director.getPositionSeconds();
        // Target is 60s; accept position near 60s
        if (pos >= 59.0f) {
            LOGGER.info("[AutoTest] Seek confirmed applied; runtime position is {}s", pos);
            transition(State.WAIT_SEEK, "vanilla:sweden", (double) pos, true, null);
        } else if (now - seekDispatchTimeMs > 2000L) {
            fail(mc, "Seek to 60s not applied within 2s; current position: " + pos);
        }
    }

    private static void handleWaitSeek(Minecraft mc, long now) {
        // In case seek didn't register immediately on the dispatch tick
        if (seekDispatchTimeMs > 0 && MusicDirector.getInstance().getPositionSeconds() < 59.0f) {
            checkSeekApplied(mc, now);
            return;
        }

        // Fixed capture window for seek: 0.5–2.5 seconds after the 60-second seek.
        // Wait 3.0s total to ensure full post-seek capture.
        if (now - stateEntryTimeMs >= 3000L) {
            transition(State.SWITCH_B, "vanilla:wet_hands", 0.0, true, null);
        }
    }

    private static void handleSwitchB(Minecraft mc, long now) {
        MusicLibrary lib = CueMyMusic.getInstance().getLibrary();
        MusicTrack trackB = lib.getTrack("vanilla:wet_hands").orElse(null);
        if (trackB == null) {
            fail(mc, "Track vanilla:wet_hands missing from library");
            return;
        }

        boolean switched = MusicDirector.getInstance().previewTrack(mc, trackB);
        if (!switched) {
            fail(mc, "previewTrack failed for vanilla:wet_hands");
            return;
        }

        LOGGER.info("[AutoTest] Switched playback to vanilla:wet_hands");
        transition(State.WAIT_B, "vanilla:wet_hands", 0.0, true, null);
    }

    private static void handleWaitB(Minecraft mc, long now) {
        // Fixed capture window for switch: 1-3 seconds after switching.
        // Wait 3.5s total to allow pre-pause slice extraction.
        if (now - stateEntryTimeMs >= 3500L) {
            transition(State.PAUSE_B, "vanilla:wet_hands", (double) MusicDirector.getInstance().getPositionSeconds(), true, null);
        }
    }

    private static void handlePauseB(Minecraft mc, long now) {
        MusicDirector director = MusicDirector.getInstance();
        double pos = director.getPositionSeconds();
        boolean toggled = director.togglePause(mc);
        if (!toggled && !director.isPaused()) {
            fail(mc, "togglePause did not pause playback");
            return;
        }

        LOGGER.info("[AutoTest] Paused vanilla:wet_hands at {}s", pos);
        transition(State.WAIT_PAUSE, "vanilla:wet_hands", pos, true, null);
    }

    private static void handleWaitPause(Minecraft mc, long now) {
        // Fixed capture window for pause: 0.5–1.5 seconds after pause.
        // Wait 2.0s total for silence settling.
        if (now - stateEntryTimeMs >= 2000L) {
            transition(State.RESUME_B, "vanilla:wet_hands", (double) MusicDirector.getInstance().getPositionSeconds(), true, null);
        }
    }

    private static void handleResumeB(Minecraft mc, long now) {
        MusicDirector director = MusicDirector.getInstance();
        double pos = director.getPositionSeconds();
        boolean toggled = director.togglePause(mc);
        if (!toggled && !director.isPlaying()) {
            fail(mc, "togglePause did not resume playback");
            return;
        }

        LOGGER.info("[AutoTest] Resumed vanilla:wet_hands at {}s", pos);
        transition(State.WAIT_RESUME, "vanilla:wet_hands", pos, true, null);
    }

    private static void handleWaitResume(Minecraft mc, long now) {
        // Fixed capture window for resume: 0.5–2.5 seconds after resume.
        // Wait 3.0s total to ensure post-resume capture completes.
        if (now - stateEntryTimeMs >= 3000L) {
            finish(mc);
        }
    }

    private static AbstractSliderButton findPlaybackSlider(Screen screen) {
        AbstractSliderButton found = null;
        for (var child : screen.children()) {
            if (child instanceof AbstractSliderButton slider && slider.visible) {
                if (found != null) {
                    return null; // More than one visible slider is ambiguous
                }
                found = slider;
            }
        }
        return found;
    }

    private static boolean isClientReady(Minecraft mc) {
        if (mc == null || mc.getWindow() == null || mc.getResourceManager() == null || mc.getSoundManager() == null) {
            return false;
        }
        if (mc.gui == null || mc.gui.screen() == null) {
            return false;
        }
        var inst = CueMyMusic.getInstance();
        if (inst == null || inst.getLibrary() == null) {
            return false;
        }
        var lib = inst.getLibrary();
        return lib.getTrack("vanilla:sweden").isPresent() && lib.getTrack("vanilla:wet_hands").isPresent();
    }

    private static void snapshotVolumes(Minecraft mc) {
        SNAPSHOTTED_VOLUMES.clear();
        for (SoundSource source : SoundSource.values()) {
            var option = mc.options.getSoundSourceOptionInstance(source);
            if (option != null) {
                SNAPSHOTTED_VOLUMES.put(source, option.get());
            }
        }
    }

    private static void applyTestSoundProfile(Minecraft mc) {
        for (SoundSource source : SoundSource.values()) {
            var option = mc.options.getSoundSourceOptionInstance(source);
            if (option != null) {
                if (source == SoundSource.MASTER || source == SoundSource.MUSIC) {
                    option.set(1.0);
                } else {
                    option.set(0.0);
                }
            }
        }
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().stop();
        }
        if (mc.getMusicManager() != null) {
            mc.getMusicManager().stopPlaying();
        }
    }

    private static void restoreVolumes(Minecraft mc) {
        for (Map.Entry<SoundSource, Double> entry : SNAPSHOTTED_VOLUMES.entrySet()) {
            try {
                var option = mc.options.getSoundSourceOptionInstance(entry.getKey());
                if (option != null) {
                    option.set(entry.getValue());
                }
            } catch (Exception ignored) {}
        }
    }

    private static synchronized void transition(State nextState, String trackId, Double positionSeconds, boolean success, String error) {
        currentState = nextState;
        stateEntryTimeMs = System.currentTimeMillis();
        currentTrackId = trackId;
        currentPosition = positionSeconds;
        writeEvent(nextState, null, trackId, positionSeconds, success, error);
        LOGGER.info("[AutoTest] Transition -> {} (track: {}, pos: {}, success: {})", nextState, trackId, positionSeconds, success);
    }

    private static void finish(Minecraft mc) {
        LOGGER.info("[AutoTest] Scenario complete. Finishing and closing client.");
        transition(State.FINISH, null, null, true, null);
        cleanupAndStop(mc);
    }

    private static void fail(Minecraft mc, String reason) {
        LOGGER.error("[AutoTest] Scenario FAILED in state {}: {}", currentState, reason);
        transition(State.FAILED, currentTrackId, currentPosition, false, reason);
        cleanupAndStop(mc);
    }

    private static void cleanupAndStop(Minecraft mc) {
        stopped = true;
        try {
            restoreVolumes(mc);
        } catch (Exception ignored) {}
        try {
            MusicDirector.getInstance().stopCurrent(mc);
        } catch (Exception ignored) {}
        try {
            if (eventsWriter != null) {
                eventsWriter.flush();
                eventsWriter.close();
            }
        } catch (Exception ignored) {}
        mc.stop();
    }

    private static synchronized void writeEvent(State state, Long pid, String trackId, Double positionSeconds, boolean success, String error) {
        long epochMs = System.currentTimeMillis();
        String json = formatEventJson(state.name(), epochMs, pid, trackId, positionSeconds, success, error);
        try {
            if (eventsWriter != null) {
                eventsWriter.write(json);
                eventsWriter.newLine();
                eventsWriter.flush();
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write event: {}", json, e);
        }
    }

    public static String formatEventJson(String phase, long epochMs, Long pid, String trackId, Double positionSeconds, boolean success, String error) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"phase\":\"").append(phase).append("\",");
        sb.append("\"epochMs\":").append(epochMs).append(",");
        sb.append("\"pid\":").append(pid == null ? "null" : pid.toString()).append(",");
        sb.append("\"trackId\":").append(trackId == null ? "null" : "\"" + trackId + "\"").append(",");
        if (positionSeconds == null) {
            sb.append("\"positionSeconds\":null,");
        } else {
            sb.append("\"positionSeconds\":").append(Double.toString(positionSeconds)).append(",");
        }
        sb.append("\"success\":").append(success).append(",");
        if (error == null) {
            sb.append("\"error\":null");
        } else {
            sb.append("\"error\":\"").append(escapeJson(error)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
