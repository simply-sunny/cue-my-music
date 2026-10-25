package mix.cuemymusic;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import mix.cuemymusic.CueConfig.TempoMode;

public final class CueConfigScreen {
    private CueConfigScreen() {
    }

    public static Screen create(Screen parent) {
        CueConfig cfg = CueConfig.INSTANCE;
        cfg.clamp();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("cuemymusic.config.title"))
                .setSavingRunnable(cfg::save);
        ConfigEntryBuilder eb = builder.entryBuilder();
        ConfigCategory main = builder.getOrCreateCategory(Component.translatable("cuemymusic.config.category.main"));

        main.addEntry(eb.startTextDescription(nowPlaying()).build());

        main.addEntry(eb.startBooleanToggle(Component.translatable("cuemymusic.option.enabled"), cfg.enabled)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enabled = v)
                .build());

        main.addEntry(eb.startIntSlider(Component.translatable("cuemymusic.option.pitch"), cfg.pitchSemitones, -12, 12)
                .setDefaultValue(0)
                .setTextGetter(v -> Component.translatable("cuemymusic.option.pitch.value", (v > 0 ? "+" : "") + v))
                .setTooltip(Component.translatable("cuemymusic.option.pitch.tooltip"))
                .setSaveConsumer(v -> cfg.pitchSemitones = v)
                .build());

        main.addEntry(eb.startEnumSelector(Component.translatable("cuemymusic.option.tempo_mode"), TempoMode.class, cfg.tempoMode)
                .setDefaultValue(TempoMode.SPEED)
                .setEnumNameProvider(m -> Component.translatable("cuemymusic.tempo_mode." + m.name().toLowerCase()))
                .setTooltip(Component.translatable("cuemymusic.option.tempo_mode.tooltip"))
                .setSaveConsumer(v -> cfg.tempoMode = v)
                .build());

        main.addEntry(eb.startDoubleField(Component.translatable("cuemymusic.option.speed"), cfg.speed)
                .setDefaultValue(1.0)
                .setMin(0.5).setMax(2.0)
                .setTooltip(Component.translatable("cuemymusic.option.speed.tooltip"))
                .setSaveConsumer(v -> cfg.speed = v)
                .build());

        main.addEntry(eb.startIntSlider(Component.translatable("cuemymusic.option.bpm_offset"), cfg.bpmOffset, -60, 60)
                .setDefaultValue(0)
                .setTextGetter(v -> Component.translatable("cuemymusic.option.bpm_offset.value", (v > 0 ? "+" : "") + v))
                .setTooltip(Component.translatable("cuemymusic.option.bpm_offset.tooltip"))
                .setSaveConsumer(v -> cfg.bpmOffset = v)
                .build());

        main.addEntry(eb.startBooleanToggle(Component.translatable("cuemymusic.option.music"), cfg.music)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("cuemymusic.option.music.tooltip"))
                .setSaveConsumer(v -> cfg.music = v)
                .build());

        main.addEntry(eb.startBooleanToggle(Component.translatable("cuemymusic.option.records"), cfg.records)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("cuemymusic.option.records.tooltip"))
                .setSaveConsumer(v -> cfg.records = v)
                .build());

        return builder.build();
    }

    private static Component nowPlaying() {
        CueStream.NowPlaying np = CueStream.nowPlaying;
        if (np == null) return Component.translatable("cuemymusic.now_playing.none");
        double bpm = np.detector().bpm();
        Component tempo = !np.detector().isDone()
                ? Component.translatable("cuemymusic.now_playing.listening")
                : Double.isNaN(bpm)
                        ? Component.translatable("cuemymusic.now_playing.no_beat")
                        : Component.translatable("cuemymusic.now_playing.bpm", String.format("%.0f", bpm));
        return Component.translatable("cuemymusic.now_playing", np.track(), tempo);
    }
}
