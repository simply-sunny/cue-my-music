package com.cuemymusic.client.ui;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.music.WeightedMusicCatalog;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * Native Minecraft screen for configuring track pools and track selection multipliers.
 * Modifications are transactional: Done persists and applies the draft, Esc discards.
 */
public final class TrackWeightScreen extends Screen {

    public record Bounds(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    public record NarrowGeometry(
            Bounds poolSearch,
            Bounds list,
            Bounds editorInfo,
            Bounds slider,
            Bounds quickButtons,
            int errorY,
            Bounds bottomRow1,
            Bounds bottomRow2) {}

    private final MusicPlayerScreen parent;
    private final TrackWeightConfig saved;
    private final Model model;
    private Component errorMessage;

    private CycleButton<Pool> poolButton;
    private EditBox searchBox;
    private TrackList trackList;
    private WeightSlider weightSlider;
    private Button btn0x;
    private Button btn05x;
    private Button btn1x;
    private Button btn2x;
    private Button btn5x;
    private Button previewButton;
    private Button allButton;
    private Button c418Button;
    private Button muteButton;
    private CycleButton<Boolean> antiRepeatButton;
    private Button testRollButton;
    private Button jsonButton;
    private Button doneButton;

    public TrackWeightScreen(MusicPlayerScreen parent) {
        this(parent, MusicDirector.getInstance().weightingConfig(), MusicDirector.getInstance().weightedCatalog().pools());
    }

    TrackWeightScreen(MusicPlayerScreen parent, TrackWeightConfig saved) {
        this(parent, saved, MusicDirector.getInstance().weightedCatalog().pools());
    }

    TrackWeightScreen(MusicPlayerScreen parent, TrackWeightConfig saved, WeightedMusicCatalog catalog) {
        this(parent, saved, catalog != null ? catalog.pools() : List.of());
    }

    TrackWeightScreen(MusicPlayerScreen parent, TrackWeightConfig saved, Pool pool) {
        this(parent, saved, pool != null ? List.of(pool) : List.of());
    }

    TrackWeightScreen(MusicPlayerScreen parent, TrackWeightConfig saved, List<Pool> pools) {
        super(Component.literal("Configure Track Pools"));
        this.parent = parent;
        this.saved = saved != null ? saved : TrackWeightConfig.defaults();
        this.model = new Model(this.saved, pools);
    }

    static boolean usesWideLayout(int width) {
        return width >= 640;
    }

    public static Bounds wideEditorBounds(int screenWidth, int screenHeight) {
        return new Bounds(screenWidth - 220, 50, 200, 130);
    }

    public static Bounds wideListBounds(int screenWidth, int screenHeight) {
        return new Bounds(20, 50, 220, screenHeight - 50 - 60);
    }

    public static Bounds wideBottomBarBounds(int screenWidth, int screenHeight) {
        int totalW = 506;
        return new Bounds((screenWidth - totalW) / 2, screenHeight - 28, totalW, 20);
    }

    public static NarrowGeometry narrowGeometry(int width, int height) {
        int margin = 10;
        int w = width - (margin * 2);

        Bounds poolSearch = new Bounds(margin, 16, w, 18);
        int listH = Math.max(36, height - 173);
        Bounds list = new Bounds(margin, 36, w, listH);
        int editorY = list.bottom() + 2;
        Bounds editorInfo = new Bounds(margin, editorY, w, 19);
        int sliderY = editorInfo.bottom() + 2;
        Bounds slider = new Bounds(margin, sliderY, Math.min(200, w), 18);
        int qbY = slider.bottom() + 2;
        Bounds quickButtons = new Bounds(margin, qbY, w, 18);

        int row2Y = height - 22;
        Bounds bottomRow2 = new Bounds(margin, row2Y, w, 18);
        int row1Y = row2Y - 22;
        Bounds bottomRow1 = new Bounds(margin, row1Y, w, 18);

        int errorY = row1Y - 14;

        return new NarrowGeometry(poolSearch, list, editorInfo, slider, quickButtons, errorY, bottomRow1, bottomRow2);
    }

    static TrackWeightConfig updateWeight(TrackWeightConfig base, String poolId, String resourceId, double multiplier) {
        return base.withMultiplier(poolId, resourceId, multiplier);
    }

    static List<Track> filterTracks(List<Track> tracks, String query) {
        if (tracks == null || tracks.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.copyOf(tracks);
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        return tracks.stream()
                .filter(t -> (t.title() != null && t.title().toLowerCase(Locale.ROOT).contains(q))
                        || (t.composer() != null && t.composer().toLowerCase(Locale.ROOT).contains(q))
                        || (t.resourceId() != null && t.resourceId().toLowerCase(Locale.ROOT).contains(q)))
                .toList();
    }

    static double sliderToMultiplier(double sliderValue) {
        double clamped = Math.clamp(sliderValue, 0.0, 1.0);
        return Math.round(clamped * 20.0) / 2.0;
    }

    static double multiplierToSlider(double multiplier) {
        double clamped = Math.clamp(multiplier, 0.0, TrackWeightConfig.MAX_MULTIPLIER);
        return clamped / TrackWeightConfig.MAX_MULTIPLIER;
    }

    static TrackWeightConfig applyAll(TrackWeightConfig base, Pool pool, double multiplier) {
        if (base == null || pool == null || pool.tracks() == null || pool.tracks().isEmpty()) {
            return base;
        }
        Map<String, Double> updates = new LinkedHashMap<>();
        for (Track track : pool.tracks()) {
            updates.put(track.resourceId(), multiplier);
        }
        return base.withPoolMultipliers(pool.id(), updates);
    }

    static TrackWeightConfig applyAll(TrackWeightConfig base, Pool pool) {
        return applyAll(base, pool, 1.0);
    }

    static TrackWeightConfig applyC418(TrackWeightConfig base, Pool pool) {
        if (base == null || pool == null || pool.tracks() == null || pool.tracks().isEmpty()) {
            return base;
        }
        Map<String, Double> updates = new LinkedHashMap<>();
        for (Track track : pool.tracks()) {
            if ("C418".equals(track.composer())) {
                updates.put(track.resourceId(), 2.0);
            }
        }
        return updates.isEmpty() ? base : base.withPoolMultipliers(pool.id(), updates);
    }

    static TrackWeightConfig mute(TrackWeightConfig base, Pool pool) {
        return applyAll(base, pool, 0.0);
    }

    static Optional<Occurrence> testRoll(Pool pool, TrackWeightConfig config, String previousResourceId, long seed) {
        return WeightedMusicCatalog.select(pool, config, previousResourceId, seed);
    }

    static Optional<Occurrence> testRoll(Pool pool, TrackWeightConfig config, long seed) {
        return testRoll(pool, config, null, seed);
    }

    static String formatMultiplier(double multiplier) {
        if (multiplier == (long) multiplier) {
            return String.valueOf((long) multiplier);
        }
        return String.format(Locale.ROOT, "%.1f", multiplier);
    }

    static String formatPercent(double chance) {
        long percent = Math.round(chance * 100.0);
        return String.valueOf(percent);
    }

    static String formatNarration(String title, String composer, double multiplier, double chance) {
        String artist = composer != null ? composer : "Unknown composer";
        return title + ", " + artist + ", " + formatMultiplier(multiplier) + "×, " + formatPercent(chance) + "%";
    }

    void selectTrack(String resourceId) {
        if (model.selectTrack(resourceId)) {
            updateSelectedTrackWidgets();
        }
    }

    private void onDraftChanged() {
        if (trackList != null) {
            trackList.refreshEntries();
        }
        updateSelectedTrackWidgets();
    }

    private void updateSelectedTrackWidgets() {
        Track track = model.selectedTrack();
        if (trackList != null && track != null) {
            trackList.selectTrackEntry(track.resourceId());
        }
        if (weightSlider != null && track != null && model.selectedPool() != null) {
            double currentMult = model.draft().multiplier(model.selectedPool().id(), track.resourceId());
            weightSlider.syncFromModel(currentMult);
        }
        if (previewButton != null) {
            previewButton.active = track != null
                    && track.occurrences() != null
                    && !track.occurrences().isEmpty();
        }
    }

    private void setQuickMultiplier(double multiplier) {
        if (model.setMultiplier(multiplier)) {
            onDraftChanged();
        }
    }

    private void onPoolChanged(Pool newPool) {
        model.switchPool(newPool);
        if (searchBox != null) {
            searchBox.setValue("");
        }
        refreshTrackList();
        updateSelectedTrackWidgets();
    }

    private void refreshTrackList() {
        if (trackList != null) {
            trackList.populate(model.filteredTracks());
        }
    }

    void saveAndClose() {
        try {
            TrackWeightConfig draft = model.draft();
            MusicDirector.getInstance().saveWeightingConfig(draft);
            if (minecraft != null) {
                minecraft.setScreenAndShow(parent);
            }
        } catch (IOException e) {
            this.errorMessage = Component.literal("Could not save cue-my-music.json");
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    protected void init() {
        super.init();

        boolean wide = usesWideLayout(width);

        if (wide) {
            initWideLayout();
        } else {
            initNarrowLayout();
        }

        refreshTrackList();
        updateSelectedTrackWidgets();
    }

    private void initWideLayout() {
        int topY = 24;
        if (!model.pools().isEmpty()) {
            poolButton = CycleButton.builder((Pool pool) -> Component.literal(pool.id()), model.selectedPool())
                    .withValues(model.pools())
                    .create(20, topY, 240, 20, Component.literal("Pool"), (btn, pool) -> onPoolChanged(pool));
            addRenderableWidget(poolButton);
        }

        searchBox = new EditBox(font, 268, topY, 180, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search track or composer…"));
        searchBox.setValue(model.searchQuery());
        searchBox.setResponder(query -> {
            model.setSearchQuery(query);
            refreshTrackList();
            updateSelectedTrackWidgets();
        });
        addRenderableWidget(searchBox);

        Bounds listBounds = wideListBounds(width, height);
        trackList = new TrackList(minecraft, listBounds.width(), listBounds.height(), listBounds.y(), 20, model, this::updateSelectedTrackWidgets);
        trackList.setX(listBounds.x());
        addRenderableWidget(trackList);

        Bounds editorBounds = wideEditorBounds(width, height);
        int rightX = editorBounds.x();
        int editorY = editorBounds.y();

        weightSlider = new WeightSlider(rightX, editorY + 54, 200, 20, model, this::onDraftChanged);
        addRenderableWidget(weightSlider);

        int qbY = editorY + 78;
        btn0x = Button.builder(Component.literal("0×"), b -> setQuickMultiplier(0.0)).bounds(rightX, qbY, 36, 20).build();
        btn05x = Button.builder(Component.literal("0.5×"), b -> setQuickMultiplier(0.5)).bounds(rightX + 41, qbY, 36, 20).build();
        btn1x = Button.builder(Component.literal("1×"), b -> setQuickMultiplier(1.0)).bounds(rightX + 82, qbY, 36, 20).build();
        btn2x = Button.builder(Component.literal("2×"), b -> setQuickMultiplier(2.0)).bounds(rightX + 123, qbY, 36, 20).build();
        btn5x = Button.builder(Component.literal("5×"), b -> setQuickMultiplier(5.0)).bounds(rightX + 164, qbY, 36, 20).build();
        addRenderableWidget(btn0x);
        addRenderableWidget(btn05x);
        addRenderableWidget(btn1x);
        addRenderableWidget(btn2x);
        addRenderableWidget(btn5x);

        previewButton = Button.builder(Component.literal("Play Sound"), b -> {}).bounds(rightX, qbY + 24, 120, 20).build();
        previewButton.active = model.selectedTrack() != null
                && model.selectedTrack().occurrences() != null
                && !model.selectedTrack().occurrences().isEmpty();
        addRenderableWidget(previewButton);

        Bounds bottomBar = wideBottomBarBounds(width, height);
        int bottomY = bottomBar.y();
        int bx = bottomBar.x();

        allButton = Button.builder(Component.literal("All 1×"), b -> {
            model.applyAll();
            onDraftChanged();
        }).bounds(bx, bottomY, 58, 20).build();
        bx += 64;

        c418Button = Button.builder(Component.literal("C418 2×"), b -> {
            model.applyC418();
            onDraftChanged();
        }).bounds(bx, bottomY, 62, 20).build();
        bx += 68;

        muteButton = Button.builder(Component.literal("Mute 0×"), b -> {
            model.mute();
            onDraftChanged();
        }).bounds(bx, bottomY, 62, 20).build();
        bx += 68;

        antiRepeatButton = CycleButton.onOffBuilder(model.draft().antiRepeat())
                .create(bx, bottomY, 110, 20, Component.literal("Anti-Repeat"), (btn, val) -> {
                    model.setAntiRepeat(val);
                    onDraftChanged();
                });
        bx += 116;

        testRollButton = Button.builder(Component.literal("Test Roll"), b -> {
            if (model.selectedPool() != null) {
                long seed = RandomSource.create().nextLong();
                Optional<Occurrence> roll = testRoll(model.selectedPool(), model.draft(), seed);
                roll.ifPresent(occ -> selectTrack(occ.resourceId()));
            }
        }).bounds(bx, bottomY, 70, 20).build();
        bx += 76;

        jsonButton = Button.builder(Component.literal("JSON"), b -> {
            if (minecraft != null) {
                minecraft.setScreenAndShow(new JsonScreen(this, model.draft()));
            }
        }).bounds(bx, bottomY, 46, 20).build();
        bx += 52;

        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(bx, bottomY, 62, 20)
                .build();

        addRenderableWidget(allButton);
        addRenderableWidget(c418Button);
        addRenderableWidget(muteButton);
        addRenderableWidget(antiRepeatButton);
        addRenderableWidget(testRollButton);
        addRenderableWidget(jsonButton);
        addRenderableWidget(doneButton);
    }

    private void initNarrowLayout() {
        NarrowGeometry geom = narrowGeometry(width, height);

        int halfW = (geom.poolSearch().width() - 4) / 2;
        if (!model.pools().isEmpty()) {
            poolButton = CycleButton.builder((Pool pool) -> Component.literal(pool.id()), model.selectedPool())
                    .withValues(model.pools())
                    .create(geom.poolSearch().x(), geom.poolSearch().y(), halfW, geom.poolSearch().height(),
                            Component.literal("Pool"), (btn, pool) -> onPoolChanged(pool));
            addRenderableWidget(poolButton);
        }

        searchBox = new EditBox(font, geom.poolSearch().x() + halfW + 4, geom.poolSearch().y(),
                halfW, geom.poolSearch().height(), Component.literal("Search"));
        searchBox.setHint(Component.literal("Search track or composer…"));
        searchBox.setValue(model.searchQuery());
        searchBox.setResponder(query -> {
            model.setSearchQuery(query);
            refreshTrackList();
            updateSelectedTrackWidgets();
        });
        addRenderableWidget(searchBox);

        trackList = new TrackList(minecraft, geom.list().width(), geom.list().height(), geom.list().y(),
                20, model, this::updateSelectedTrackWidgets);
        trackList.setX(geom.list().x());
        addRenderableWidget(trackList);

        weightSlider = new WeightSlider(geom.slider().x(), geom.slider().y(), geom.slider().width(),
                geom.slider().height(), model, this::onDraftChanged);
        addRenderableWidget(weightSlider);

        int qbX = geom.quickButtons().x();
        int qbY = geom.quickButtons().y();
        int qbW = (Math.min(200, geom.quickButtons().width()) - 16) / 5;
        btn0x = Button.builder(Component.literal("0×"), b -> setQuickMultiplier(0.0)).bounds(qbX, qbY, qbW, 18).build();
        btn05x = Button.builder(Component.literal("0.5×"), b -> setQuickMultiplier(0.5)).bounds(qbX + (qbW + 4), qbY, qbW, 18).build();
        btn1x = Button.builder(Component.literal("1×"), b -> setQuickMultiplier(1.0)).bounds(qbX + (qbW + 4) * 2, qbY, qbW, 18).build();
        btn2x = Button.builder(Component.literal("2×"), b -> setQuickMultiplier(2.0)).bounds(qbX + (qbW + 4) * 3, qbY, qbW, 18).build();
        btn5x = Button.builder(Component.literal("5×"), b -> setQuickMultiplier(5.0)).bounds(qbX + (qbW + 4) * 4, qbY, qbW, 18).build();
        addRenderableWidget(btn0x);
        addRenderableWidget(btn05x);
        addRenderableWidget(btn1x);
        addRenderableWidget(btn2x);
        addRenderableWidget(btn5x);

        int previewX = qbX + (qbW + 4) * 5 + 4;
        int previewW = Math.max(60, geom.quickButtons().right() - previewX);
        previewButton = Button.builder(Component.literal("Play Sound"), b -> {}).bounds(previewX, qbY, previewW, 18).build();
        previewButton.active = model.selectedTrack() != null
                && model.selectedTrack().occurrences() != null
                && !model.selectedTrack().occurrences().isEmpty();
        addRenderableWidget(previewButton);

        int row1BtnW = (geom.bottomRow1().width() - 9) / 4;
        int r1X = geom.bottomRow1().x();
        int r1Y = geom.bottomRow1().y();

        allButton = Button.builder(Component.literal("All 1×"), b -> {
            model.applyAll();
            onDraftChanged();
        }).bounds(r1X, r1Y, row1BtnW, 18).build();

        c418Button = Button.builder(Component.literal("C418 2×"), b -> {
            model.applyC418();
            onDraftChanged();
        }).bounds(r1X + (row1BtnW + 3), r1Y, row1BtnW, 18).build();

        muteButton = Button.builder(Component.literal("Mute 0×"), b -> {
            model.mute();
            onDraftChanged();
        }).bounds(r1X + (row1BtnW + 3) * 2, r1Y, row1BtnW, 18).build();

        antiRepeatButton = CycleButton.onOffBuilder(model.draft().antiRepeat())
                .create(r1X + (row1BtnW + 3) * 3, r1Y, row1BtnW, 18, Component.literal("Anti-Repeat"), (btn, val) -> {
                    model.setAntiRepeat(val);
                    onDraftChanged();
                });

        addRenderableWidget(allButton);
        addRenderableWidget(c418Button);
        addRenderableWidget(muteButton);
        addRenderableWidget(antiRepeatButton);

        int row2BtnW = (geom.bottomRow2().width() - 6) / 3;
        int r2X = geom.bottomRow2().x();
        int r2Y = geom.bottomRow2().y();

        testRollButton = Button.builder(Component.literal("Test Roll"), b -> {
            if (model.selectedPool() != null) {
                long seed = RandomSource.create().nextLong();
                Optional<Occurrence> roll = testRoll(model.selectedPool(), model.draft(), seed);
                roll.ifPresent(occ -> selectTrack(occ.resourceId()));
            }
        }).bounds(r2X, r2Y, row2BtnW, 18).build();

        jsonButton = Button.builder(Component.literal("JSON"), b -> {
            if (minecraft != null) {
                minecraft.setScreenAndShow(new JsonScreen(this, model.draft()));
            }
        }).bounds(r2X + (row2BtnW + 3), r2Y, row2BtnW, 18).build();

        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(r2X + (row2BtnW + 3) * 2, r2Y, row2BtnW, 18)
                .build();

        addRenderableWidget(testRollButton);
        addRenderableWidget(jsonButton);
        addRenderableWidget(doneButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
        super.extractRenderState(extractor, mouseX, mouseY, tickProgress);

        boolean wide = usesWideLayout(width);
        extractor.centeredText(font, title, width / 2, wide ? 10 : 4, 0xFFFFFF);

        Track track = model.selectedTrack();
        if (track != null) {
            double chance = model.currentChances().getOrDefault(track.resourceId(), 0.0);
            if (wide) {
                Bounds editorBounds = wideEditorBounds(width, height);
                int rightX = editorBounds.x();
                int editorY = editorBounds.y();
                extractor.text(font, track.title(), rightX, editorY, 0xFFFFFF);
                String comp = track.composer() != null ? "Composer: " + track.composer() : "No Composer";
                extractor.text(font, comp, rightX, editorY + 12, 0xAAAAAA);
                extractor.text(font, track.resourceId(), rightX, editorY + 24, 0x888888);
                extractor.text(font, "Chance: " + formatPercent(chance) + "%", rightX, editorY + 38, 0x55FF55);
            } else {
                NarrowGeometry geom = narrowGeometry(width, height);
                int editorY = geom.editorInfo().y();
                String line1 = track.title() + " (" + formatPercent(chance) + "%)";
                extractor.text(font, line1, geom.editorInfo().x(), editorY, 0xFFFFFF);
                String comp = track.composer() != null ? track.composer() : track.resourceId();
                extractor.text(font, comp, geom.editorInfo().x(), editorY + 10, 0xAAAAAA);
            }
        }

        if (errorMessage != null) {
            int errorY = wide ? height - 42 : narrowGeometry(width, height).errorY();
            extractor.centeredText(font, errorMessage, width / 2, errorY, 0xFF5555);
        }
    }

    Pool selectedPool() {
        return model.selectedPool();
    }

    Track selectedTrack() {
        return model.selectedTrack();
    }

    TrackWeightConfig draft() {
        return model.draft();
    }

    TrackWeightConfig saved() {
        return saved;
    }

    Component errorMessage() {
        return errorMessage;
    }

    MusicPlayerScreen parent() {
        return parent;
    }

    Model model() {
        return model;
    }

    public static final class Model {
        private final List<Pool> pools;
        private Pool selectedPool;
        private Track selectedTrack;
        private TrackWeightConfig draft;
        private String searchQuery = "";
        private Map<String, Double> currentChances = Map.of();

        public Model(TrackWeightConfig saved, List<Pool> pools) {
            this.pools = pools != null ? List.copyOf(pools) : List.of();
            this.draft = saved != null ? saved : TrackWeightConfig.defaults();
            this.selectedPool = !this.pools.isEmpty() ? this.pools.getFirst() : null;
            this.selectedTrack = this.selectedPool != null && this.selectedPool.tracks() != null && !this.selectedPool.tracks().isEmpty()
                    ? this.selectedPool.tracks().getFirst()
                    : null;
            recomputeChances();
        }

        public void recomputeChances() {
            if (selectedPool != null) {
                this.currentChances = WeightedMusicCatalog.chances(selectedPool, draft, null);
            } else {
                this.currentChances = Map.of();
            }
        }

        public boolean selectTrack(String resourceId) {
            if (selectedPool == null || resourceId == null || selectedPool.tracks() == null) {
                return false;
            }
            for (Track track : selectedPool.tracks()) {
                if (resourceId.equals(track.resourceId())) {
                    this.selectedTrack = track;
                    return true;
                }
            }
            return false;
        }

        public void switchPool(Pool newPool) {
            this.selectedPool = newPool;
            this.searchQuery = "";
            this.selectedTrack = newPool != null && newPool.tracks() != null && !newPool.tracks().isEmpty()
                    ? newPool.tracks().getFirst()
                    : null;
            recomputeChances();
        }

        public List<Track> filteredTracks() {
            List<Track> all = selectedPool != null && selectedPool.tracks() != null ? selectedPool.tracks() : List.of();
            return filterTracks(all, searchQuery);
        }

        public void setSearchQuery(String query) {
            this.searchQuery = query != null ? query : "";
            List<Track> filtered = filteredTracks();
            if (selectedTrack == null || filtered.stream().noneMatch(t -> t.resourceId().equals(selectedTrack.resourceId()))) {
                this.selectedTrack = !filtered.isEmpty() ? filtered.getFirst() : null;
            }
        }

        public boolean setMultiplier(double multiplier) {
            if (selectedPool == null || selectedTrack == null) {
                return false;
            }
            double clamped = Math.clamp(multiplier, 0.0, TrackWeightConfig.MAX_MULTIPLIER);
            this.draft = this.draft.withMultiplier(selectedPool.id(), selectedTrack.resourceId(), clamped);
            recomputeChances();
            return true;
        }

        public void applyAll() {
            if (selectedPool != null) {
                this.draft = TrackWeightScreen.applyAll(this.draft, selectedPool);
                recomputeChances();
            }
        }

        public void applyC418() {
            if (selectedPool != null) {
                this.draft = TrackWeightScreen.applyC418(this.draft, selectedPool);
                recomputeChances();
            }
        }

        public void mute() {
            if (selectedPool != null) {
                this.draft = TrackWeightScreen.mute(this.draft, selectedPool);
                recomputeChances();
            }
        }

        public void setAntiRepeat(boolean antiRepeat) {
            this.draft = this.draft.withAntiRepeat(antiRepeat);
            recomputeChances();
        }

        public Pool selectedPool() { return selectedPool; }
        public Track selectedTrack() { return selectedTrack; }
        public TrackWeightConfig draft() { return draft; }
        public String searchQuery() { return searchQuery; }
        public Map<String, Double> currentChances() { return currentChances; }
        public List<Pool> pools() { return pools; }
    }

    public static final class WeightSlider extends AbstractSliderButton {
        private final Model sliderModel;
        private final Runnable onDraftChanged;
        private boolean updating;

        public WeightSlider(int x, int y, int width, int height, Model sliderModel, Runnable onDraftChanged) {
            super(x, y, width, height, Component.empty(),
                    sliderModel != null && sliderModel.selectedTrack() != null && sliderModel.selectedPool() != null
                            ? multiplierToSlider(sliderModel.draft().multiplier(sliderModel.selectedPool().id(), sliderModel.selectedTrack().resourceId()))
                            : 0.1);
            this.sliderModel = sliderModel;
            this.onDraftChanged = onDraftChanged;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double mult = sliderToMultiplier(this.value);
            setMessage(Component.literal("Weight: " + formatMultiplier(mult) + "×"));
        }

        @Override
        protected void applyValue() {
            if (updating) {
                return;
            }
            updating = true;
            try {
                double mult = sliderToMultiplier(this.value);
                if (sliderModel != null && sliderModel.setMultiplier(mult)) {
                    if (onDraftChanged != null) {
                        onDraftChanged.run();
                    }
                }
            } finally {
                updating = false;
            }
        }

        public void syncFromModel(double multiplier) {
            double target = multiplierToSlider(multiplier);
            if (Math.abs(this.value - target) < 1e-6) {
                return;
            }
            boolean wasUpdating = updating;
            updating = true;
            try {
                setValue(target);
            } finally {
                updating = wasUpdating;
            }
        }

        public double sliderValue() {
            return this.value;
        }
    }

    public static final class TrackList extends ObjectSelectionList<TrackEntry> {
        private final Model listModel;
        private final Runnable onSelectionChanged;
        private TrackEntry selectedEntry;
        private boolean updatingSelection;

        public TrackList(Minecraft minecraft, int width, int height, int y, int itemHeight, Model listModel, Runnable onSelectionChanged) {
            super(minecraft, width, height, y, itemHeight);
            this.listModel = listModel;
            this.onSelectionChanged = onSelectionChanged;
        }

        public void populate(List<Track> tracks) {
            clearEntries();
            for (Track track : tracks) {
                TrackEntry entry = new TrackEntry(this, track);
                addEntry(entry);
                if (listModel != null && listModel.selectedTrack() != null
                        && track.resourceId().equals(listModel.selectedTrack().resourceId())) {
                    setSelected(entry);
                }
            }
        }

        @Override
        public TrackEntry getSelected() {
            return this.minecraft != null ? super.getSelected() : selectedEntry;
        }

        @Override
        public void setSelected(TrackEntry entry) {
            this.selectedEntry = entry;
            if (this.minecraft != null) {
                super.setSelected(entry);
            }
            if (!updatingSelection && entry != null && listModel != null) {
                Track current = listModel.selectedTrack();
                if (current == null || !current.resourceId().equals(entry.track.resourceId())) {
                    updatingSelection = true;
                    try {
                        listModel.selectTrack(entry.track.resourceId());
                        if (onSelectionChanged != null) {
                            onSelectionChanged.run();
                        }
                    } finally {
                        updatingSelection = false;
                    }
                }
            }
        }

        public void selectTrackEntry(String resourceId) {
            if (resourceId == null) {
                return;
            }
            for (TrackEntry entry : children()) {
                if (entry.track.resourceId().equals(resourceId)) {
                    setSelected(entry);
                    break;
                }
            }
        }

        public void refreshEntries() {
            for (TrackEntry entry : children()) {
                entry.refresh();
            }
        }

        net.minecraft.client.gui.Font font() {
            return this.minecraft != null ? this.minecraft.font : null;
        }
    }

    public static final class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
        final TrackList list;
        final Track track;
        private double multiplier;
        private double chance;

        public TrackEntry(TrackList list, Track track) {
            this.list = list;
            this.track = track;
            refresh();
        }

        void refresh() {
            this.multiplier = list.listModel != null && list.listModel.selectedPool() != null
                    ? list.listModel.draft().multiplier(list.listModel.selectedPool().id(), track.resourceId())
                    : 1.0;
            this.chance = list.listModel != null ? list.listModel.currentChances().getOrDefault(track.resourceId(), 0.0) : 0.0;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float tickProgress) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int textY = y + (h - 9) / 2;

            net.minecraft.client.gui.Font font = list.font();
            if (font != null) {
                graphics.text(font, track.title(), x + 4, textY, 0xFFFFFF);

                String rightText = formatMultiplier(multiplier) + "×  " + formatPercent(chance) + "%";
                int textWidth = font.width(rightText);
                graphics.text(font, rightText, x + w - textWidth - 6, textY, 0xAAAAAA);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (list != null) {
                list.setSelected(this);
            }
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(formatNarration(track.title(), track.composer(), multiplier, chance));
        }
    }

    public static final class JsonScreen extends Screen {
        private final Screen parent;
        private final String json;

        public JsonScreen(Screen parent, TrackWeightConfig draft) {
            this(parent, draft != null ? draft.toJson() : "");
        }

        public JsonScreen(Screen parent, String json) {
            super(Component.literal("Track Weighting JSON"));
            this.parent = parent;
            this.json = json != null ? json : "";
        }

        public static String enforceReadOnly(String original, String incoming) {
            return original != null ? original : "";
        }

        public static void copyToClipboard(String text, java.util.function.Consumer<String> clipboardSetter) {
            if (clipboardSetter != null && text != null) {
                clipboardSetter.accept(text);
            }
        }

        @Override
        protected void init() {
            super.init();
            int boxWidth = Math.min(width - 40, 500);
            int boxHeight = height - 80;
            int boxX = (width - boxWidth) / 2;
            int boxY = 32;

            MultiLineEditBox jsonBox = MultiLineEditBox.builder()
                    .setX(boxX)
                    .setY(boxY)
                    .setShowBackground(true)
                    .build(font, boxWidth, boxHeight, title);
            jsonBox.setValue(json);
            jsonBox.setValueListener(text -> {
                if (!text.equals(json)) {
                    jsonBox.setValue(enforceReadOnly(json, text));
                }
            });
            addRenderableWidget(jsonBox);

            int buttonWidth = 120;
            int buttonHeight = 20;
            int buttonY = height - 36;
            int spacing = 10;
            int totalButtonsWidth = (buttonWidth * 2) + spacing;
            int startX = (width - totalButtonsWidth) / 2;

            addRenderableWidget(Button.builder(Component.literal("Copy"), button -> {
                if (minecraft != null) {
                    copyToClipboard(json, minecraft.keyboardHandler::setClipboard);
                }
            }).bounds(startX, buttonY, buttonWidth, buttonHeight).build());

            addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                    .bounds(startX + buttonWidth + spacing, buttonY, buttonWidth, buttonHeight)
                    .build());
        }

        @Override
        public void onClose() {
            if (minecraft != null) {
                minecraft.setScreenAndShow(parent);
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
            super.extractRenderState(extractor, mouseX, mouseY, tickProgress);
            extractor.centeredText(font, title, width / 2, 14, 0xFFFFFF);
        }
    }
}
