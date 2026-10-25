package mix.cuemymusic;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import mix.cuemymusic.dsp.PitchTempoProcessor;

/** Settings, stored as {@code config/cuemymusic.json}. Read live by the audio thread. */
public final class CueConfig {
    public static final CueConfig INSTANCE = new CueConfig();
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum TempoMode { SPEED, BPM }

    public volatile boolean enabled = true;
    public volatile boolean music = true;
    public volatile boolean records = true;
    public volatile int pitchSemitones = 0;
    public volatile TempoMode tempoMode = TempoMode.SPEED;
    public volatile double speed = 1.0;
    public volatile int bpmOffset = 0;
    /** BPM assumed when a song has no clear beat. JSON only. */
    public volatile double fallbackBpm = 120;

    private CueConfig() {
    }

    public double pitchRatio() {
        return PitchTempoProcessor.semitonesToRatio(pitchSemitones);
    }

    /** Speed factor for a song with the given detected BPM (NaN if none or not known yet). */
    public double tempoFor(double detectedBpm) {
        if (tempoMode == TempoMode.SPEED) return speed;
        double bpm = Double.isNaN(detectedBpm) ? fallbackBpm : detectedBpm;
        return Math.clamp((bpm + bpmOffset) / bpm, 0.5, 2.0);
    }

    public void clamp() {
        pitchSemitones = Math.clamp(pitchSemitones, -12, 12);
        speed = Math.clamp(Double.isFinite(speed) ? speed : 1, 0.5, 2.0);
        bpmOffset = Math.clamp(bpmOffset, -60, 60);
        fallbackBpm = Math.clamp(Double.isFinite(fallbackBpm) ? fallbackBpm : 120, 40, 300);
        if (tempoMode == null) tempoMode = TempoMode.SPEED;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("cuemymusic.json");
    }

    public void load() {
        Path path = path();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                CueConfig loaded = GSON.fromJson(r, CueConfig.class);
                if (loaded != null) copyFrom(loaded);
            } catch (IOException | JsonParseException e) {
                LOG.warn("Couldn't read {}, using defaults", path, e);
            }
        }
        clamp();
        save();
    }

    public void save() {
        clamp();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            LOG.warn("Couldn't save {}", path, e);
        }
    }

    private void copyFrom(CueConfig o) {
        enabled = o.enabled;
        music = o.music;
        records = o.records;
        pitchSemitones = o.pitchSemitones;
        tempoMode = o.tempoMode;
        speed = o.speed;
        bpmOffset = o.bpmOffset;
        fallbackBpm = o.fallbackBpm;
    }
}
