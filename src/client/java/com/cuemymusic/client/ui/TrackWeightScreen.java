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

    public record Bounds(int x, int y, int width, int height) {}

    private final MusicPlayerScreen parent;
    private final TrackWeightConfig saved;
    private final List<Pool> pools;
    private TrackWeightConfig draft;
    private Component errorMessage;

    private Pool selectedPool;
    private Track selectedTrack;
    private String searchQuery = "";
    private Map<String, Double> currentChances = Map.of();

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
        this.draft = this.saved;
        this.pools = pools != null ? List.copyOf(pools) : List.of();
        if (!this.pools.isEmpty()) {
            this.selectedPool = this.pools.getFirst();
            if (this.selectedPool.tracks() != null && !this.selectedPool.tracks().isEmpty()) {
                this.selectedTrack = this.selectedPool.tracks().getFirst();
            }
        }
        recomputeChances();
    }

    static boolean usesWideLayout(int width) {
        return width >= 640;
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

    private void recomputeChances() {
        if (selectedPool != null) {
            this.currentChances = WeightedMusicCatalog.chances(selectedPool, draft, null);
        } else {
            this.currentChances = Map.of();
        }
    }

    void selectTrack(String resourceId) {
        if (selectedPool == null || resourceId == null || selectedPool.tracks() == null) {
            return;
        }
        for (Track track : selectedPool.tracks()) {
            if (resourceId.equals(track.resourceId())) {
                this.selectedTrack = track;
                break;
            }
        }
        updateSelectedTrackWidgets();
    }

    private void onDraftChanged() {
        recomputeChances();
        if (trackList != null) {
            trackList.refreshEntries();
        }
        updateSelectedTrackWidgets();
    }

    private void updateSelectedTrackWidgets() {
        if (trackList != null && selectedTrack != null) {
            trackList.selectTrackEntry(selectedTrack.resourceId());
        }
        if (weightSlider != null && selectedTrack != null && selectedPool != null) {
            double currentMult = draft.multiplier(selectedPool.id(), selectedTrack.resourceId());
            weightSlider.setValue(multiplierToSlider(currentMult));
        }
        if (previewButton != null) {
            previewButton.active = selectedTrack != null
                    && selectedTrack.occurrences() != null
                    && !selectedTrack.occurrences().isEmpty();
        }
    }

    private void setQuickMultiplier(double multiplier) {
        if (selectedPool != null && selectedTrack != null) {
            draft = draft.withMultiplier(selectedPool.id(), selectedTrack.resourceId(), multiplier);
            onDraftChanged();
        }
    }

    private void onPoolChanged(Pool newPool) {
        this.selectedPool = newPool;
        this.selectedTrack = newPool != null && newPool.tracks() != null && !newPool.tracks().isEmpty()
                ? newPool.tracks().getFirst()
                : null;
        recomputeChances();
        refreshTrackList();
        updateSelectedTrackWidgets();
    }

    private void refreshTrackList() {
        if (trackList != null) {
            List<Track> allTracks = selectedPool != null && selectedPool.tracks() != null
                    ? selectedPool.tracks()
                    : List.of();
            List<Track> filtered = filterTracks(allTracks, searchQuery);
            trackList.populate(filtered);
        }
    }

    void saveAndClose() {
        try {
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
        if (!pools.isEmpty()) {
            poolButton = CycleButton.builder((Pool pool) -> Component.literal(pool.id()), selectedPool)
                    .withValues(pools)
                    .create(20, topY, 240, 20, Component.literal("Pool"), (btn, pool) -> onPoolChanged(pool));
            addRenderableWidget(poolButton);
        }

        searchBox = new EditBox(font, 268, topY, 180, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search track or composer…"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(query -> {
            this.searchQuery = query;
            refreshTrackList();
        });
        addRenderableWidget(searchBox);

        int listX = 20;
        int listY = 50;
        int listWidth = 280;
        int listHeight = height - listY - 60;
        trackList = new TrackList(minecraft, listWidth, listHeight, listY, 20);
        trackList.setX(listX);
        addRenderableWidget(trackList);

        int rightX = Math.max(480, width - 220);
        int editorY = 50;

        weightSlider = new WeightSlider(rightX, editorY + 54, 200, 20);
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
        previewButton.active = selectedTrack != null && selectedTrack.occurrences() != null && !selectedTrack.occurrences().isEmpty();
        addRenderableWidget(previewButton);

        int bottomY = height - 28;
        int bx = (width - 531) / 2;

        allButton = Button.builder(Component.literal("All 1×"), b -> {
            if (selectedPool != null) {
                draft = applyAll(draft, selectedPool);
                onDraftChanged();
            }
        }).bounds(bx, bottomY, 60, 20).build();
        bx += 66;

        c418Button = Button.builder(Component.literal("C418 2×"), b -> {
            if (selectedPool != null) {
                draft = applyC418(draft, selectedPool);
                onDraftChanged();
            }
        }).bounds(bx, bottomY, 65, 20).build();
        bx += 71;

        muteButton = Button.builder(Component.literal("Mute 0×"), b -> {
            if (selectedPool != null) {
                draft = mute(draft, selectedPool);
                onDraftChanged();
            }
        }).bounds(bx, bottomY, 65, 20).build();
        bx += 71;

        antiRepeatButton = CycleButton.onOffBuilder(draft.antiRepeat())
                .create(bx, bottomY, 115, 20, Component.literal("Anti-Repeat"), (btn, val) -> {
                    draft = draft.withAntiRepeat(val);
                    onDraftChanged();
                });
        bx += 121;

        testRollButton = Button.builder(Component.literal("Test Roll"), b -> {
            if (selectedPool != null) {
                long seed = RandomSource.create().nextLong();
                Optional<Occurrence> roll = testRoll(selectedPool, draft, seed);
                roll.ifPresent(occ -> selectTrack(occ.resourceId()));
            }
        }).bounds(bx, bottomY, 75, 20).build();
        bx += 81;

        jsonButton = Button.builder(Component.literal("JSON"), b -> {
            if (minecraft != null) {
                minecraft.setScreenAndShow(new JsonScreen(this, draft));
            }
        }).bounds(bx, bottomY, 50, 20).build();
        bx += 56;

        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(bx, bottomY, 65, 20)
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
        int topY = 20;
        int halfW = (width - 24) / 2;
        if (!pools.isEmpty()) {
            poolButton = CycleButton.builder((Pool pool) -> Component.literal(pool.id()), selectedPool)
                    .withValues(pools)
                    .create(10, topY, halfW, 20, Component.literal("Pool"), (btn, pool) -> onPoolChanged(pool));
            addRenderableWidget(poolButton);
        }

        searchBox = new EditBox(font, 14 + halfW, topY, halfW, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search track or composer…"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(query -> {
            this.searchQuery = query;
            refreshTrackList();
        });
        addRenderableWidget(searchBox);

        int listX = 10;
        int listY = 44;
        int listWidth = width - 20;
        int listHeight = Math.max(50, height - 190);
        trackList = new TrackList(minecraft, listWidth, listHeight, listY, 20);
        trackList.setX(listX);
        addRenderableWidget(trackList);

        int editorY = listY + listHeight + 4;
        weightSlider = new WeightSlider(listX, editorY + 22, Math.min(200, width - 20), 20);
        addRenderableWidget(weightSlider);

        int qbY = editorY + 44;
        btn0x = Button.builder(Component.literal("0×"), b -> setQuickMultiplier(0.0)).bounds(listX, qbY, 28, 20).build();
        btn05x = Button.builder(Component.literal("0.5×"), b -> setQuickMultiplier(0.5)).bounds(listX + 31, qbY, 34, 20).build();
        btn1x = Button.builder(Component.literal("1×"), b -> setQuickMultiplier(1.0)).bounds(listX + 68, qbY, 28, 20).build();
        btn2x = Button.builder(Component.literal("2×"), b -> setQuickMultiplier(2.0)).bounds(listX + 99, qbY, 28, 20).build();
        btn5x = Button.builder(Component.literal("5×"), b -> setQuickMultiplier(5.0)).bounds(listX + 130, qbY, 28, 20).build();
        addRenderableWidget(btn0x);
        addRenderableWidget(btn05x);
        addRenderableWidget(btn1x);
        addRenderableWidget(btn2x);
        addRenderableWidget(btn5x);

        previewButton = Button.builder(Component.literal("Play Sound"), b -> {}).bounds(listX + 162, qbY, 80, 20).build();
        previewButton.active = selectedTrack != null && selectedTrack.occurrences() != null && !selectedTrack.occurrences().isEmpty();
        addRenderableWidget(previewButton);

        int row1Y = height - 46;
        int row1BtnW = (width - 20 - 9) / 4;

        allButton = Button.builder(Component.literal("All 1×"), b -> {
            if (selectedPool != null) {
                draft = applyAll(draft, selectedPool);
                onDraftChanged();
            }
        }).bounds(listX, row1Y, row1BtnW, 20).build();

        c418Button = Button.builder(Component.literal("C418 2×"), b -> {
            if (selectedPool != null) {
                draft = applyC418(draft, selectedPool);
                onDraftChanged();
            }
        }).bounds(listX + (row1BtnW + 3), row1Y, row1BtnW, 20).build();

        muteButton = Button.builder(Component.literal("Mute 0×"), b -> {
            if (selectedPool != null) {
                draft = mute(draft, selectedPool);
                onDraftChanged();
            }
        }).bounds(listX + (row1BtnW + 3) * 2, row1Y, row1BtnW, 20).build();

        antiRepeatButton = CycleButton.onOffBuilder(draft.antiRepeat())
                .create(listX + (row1BtnW + 3) * 3, row1Y, row1BtnW, 20, Component.literal("Anti-Repeat"), (btn, val) -> {
                    draft = draft.withAntiRepeat(val);
                    onDraftChanged();
                });

        addRenderableWidget(allButton);
        addRenderableWidget(c418Button);
        addRenderableWidget(muteButton);
        addRenderableWidget(antiRepeatButton);

        int row2Y = height - 24;
        int row2BtnW = (width - 20 - 6) / 3;

        testRollButton = Button.builder(Component.literal("Test Roll"), b -> {
            if (selectedPool != null) {
                long seed = RandomSource.create().nextLong();
                Optional<Occurrence> roll = testRoll(selectedPool, draft, seed);
                roll.ifPresent(occ -> selectTrack(occ.resourceId()));
            }
        }).bounds(listX, row2Y, row2BtnW, 20).build();

        jsonButton = Button.builder(Component.literal("JSON"), b -> {
            if (minecraft != null) {
                minecraft.setScreenAndShow(new JsonScreen(this, draft));
            }
        }).bounds(listX + (row2BtnW + 3), row2Y, row2BtnW, 20).build();

        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(listX + (row2BtnW + 3) * 2, row2Y, row2BtnW, 20)
                .build();

        addRenderableWidget(testRollButton);
        addRenderableWidget(jsonButton);
        addRenderableWidget(doneButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
        super.extractRenderState(extractor, mouseX, mouseY, tickProgress);

        boolean wide = usesWideLayout(width);
        extractor.centeredText(font, title, width / 2, wide ? 10 : 6, 0xFFFFFF);

        if (selectedTrack != null) {
            double chance = currentChances.getOrDefault(selectedTrack.resourceId(), 0.0);
            if (wide) {
                int rightX = Math.max(480, width - 220);
                int editorY = 50;
                extractor.text(font, selectedTrack.title(), rightX, editorY, 0xFFFFFF);
                String comp = selectedTrack.composer() != null ? "Composer: " + selectedTrack.composer() : "No Composer";
                extractor.text(font, comp, rightX, editorY + 12, 0xAAAAAA);
                extractor.text(font, selectedTrack.resourceId(), rightX, editorY + 24, 0x888888);
                extractor.text(font, "Chance: " + formatPercent(chance) + "%", rightX, editorY + 38, 0x55FF55);
            } else {
                int listX = 10;
                int listY = 44;
                int listHeight = Math.max(50, height - 190);
                int editorY = listY + listHeight + 4;
                String line1 = selectedTrack.title() + " (" + formatPercent(chance) + "%)";
                extractor.text(font, line1, listX, editorY, 0xFFFFFF);
                String comp = selectedTrack.composer() != null ? selectedTrack.composer() : selectedTrack.resourceId();
                extractor.text(font, comp, listX, editorY + 11, 0xAAAAAA);
            }
        }

        if (errorMessage != null) {
            extractor.centeredText(font, errorMessage, width / 2, height - (wide ? 42 : 60), 0xFF5555);
        }
    }

    Pool selectedPool() {
        return selectedPool;
    }

    Track selectedTrack() {
        return selectedTrack;
    }

    TrackWeightConfig draft() {
        return draft;
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

    public final class WeightSlider extends AbstractSliderButton {
        public WeightSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(),
                    selectedTrack != null && selectedPool != null
                            ? multiplierToSlider(draft.multiplier(selectedPool.id(), selectedTrack.resourceId()))
                            : 0.1);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double mult = sliderToMultiplier(this.value);
            setMessage(Component.literal("Weight: " + formatMultiplier(mult) + "×"));
        }

        @Override
        protected void applyValue() {
            double mult = sliderToMultiplier(this.value);
            if (selectedPool != null && selectedTrack != null) {
                draft = draft.withMultiplier(selectedPool.id(), selectedTrack.resourceId(), mult);
                onDraftChanged();
            }
        }

        @Override
        public void setValue(double value) {
            super.setValue(value);
            updateMessage();
        }
    }

    public final class TrackList extends ObjectSelectionList<TrackEntry> {
        public TrackList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        public void populate(List<Track> tracks) {
            clearEntries();
            for (Track track : tracks) {
                TrackEntry entry = new TrackEntry(track);
                addEntry(entry);
                if (selectedTrack != null && track.resourceId().equals(selectedTrack.resourceId())) {
                    setSelected(entry);
                }
            }
        }

        public void selectTrackEntry(String resourceId) {
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
    }

    public final class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
        final Track track;
        private double multiplier;
        private double chance;

        TrackEntry(Track track) {
            this.track = track;
            refresh();
        }

        void refresh() {
            this.multiplier = selectedPool != null ? draft.multiplier(selectedPool.id(), track.resourceId()) : 1.0;
            this.chance = currentChances.getOrDefault(track.resourceId(), 0.0);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float tickProgress) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int textY = y + (h - 9) / 2;

            graphics.text(font, track.title(), x + 4, textY, 0xFFFFFF);

            String rightText = formatMultiplier(multiplier) + "×  " + formatPercent(chance) + "%";
            int textWidth = font.width(rightText);
            graphics.text(font, rightText, x + w - textWidth - 6, textY, 0xAAAAAA);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            selectTrack(track.resourceId());
            return true;
        }

        @Override
        public Component getNarration() {
            String composer = track.composer() != null ? track.composer() : "Unknown composer";
            return Component.literal(track.title() + ", " + composer + ", " + formatMultiplier(multiplier) + "×, " + formatPercent(chance) + "%");
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
                    jsonBox.setValue(json);
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
                    minecraft.keyboardHandler.setClipboard(json);
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
