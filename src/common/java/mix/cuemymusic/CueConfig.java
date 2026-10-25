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

import mix.cuemymusic.dsp.Equalizer;
import mix.cuemymusic.dsp.PitchTempoProcessor;

/** Settings, stored as {@code config/cuemymusic.json}. Read live by the audio thread. */
public final class CueConfig {
    public static final CueConfig INSTANCE = new CueConfig();
    private static final Logger LOG = LoggerFactory.getLogger("cuemymusic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public volatile boolean enabled = true;
    public volatile int pitchSemitones = 0;
    public volatile double speed = 1.0;
    public volatile boolean eqEnabled = false;
    /** dB per band, see {@link Equalizer#FREQUENCIES}. Replaced whole, never mutated in place. */
    public volatile double[] eqGains = new double[Equalizer.FREQUENCIES.length];
    public volatile String eqPreset = "flat";

    private CueConfig() {
    }

    public double pitchRatio() {
        return enabled ? PitchTempoProcessor.semitonesToRatio(pitchSemitones) : 1;
    }

    public double tempo() {
        return enabled ? speed : 1;
    }

    /** Band gains to apply right now, or null for none. */
    public double[] activeEq() {
        return enabled && eqEnabled ? eqGains : null;
    }

    public void clamp() {
        pitchSemitones = Math.clamp(pitchSemitones, -12, 12);
        speed = Math.clamp(Double.isFinite(speed) ? Math.round(speed * 100) / 100.0 : 1, 0.5, 2.0);
        double[] g = new double[Equalizer.FREQUENCIES.length];
        for (int i = 0; i < g.length; i++) {
            double v = eqGains != null && i < eqGains.length && Double.isFinite(eqGains[i]) ? eqGains[i] : 0;
            g[i] = Math.clamp(v, -Equalizer.MAX_GAIN_DB, Equalizer.MAX_GAIN_DB);
        }
        eqGains = g;
        if (eqPreset == null) eqPreset = "custom";
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
        pitchSemitones = o.pitchSemitones;
        speed = o.speed;
        eqEnabled = o.eqEnabled;
        eqGains = o.eqGains;
        eqPreset = o.eqPreset;
    }
}
