package com.cuemymusic.client.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.PinnedMusicInstance;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.music.WeightedMusicCatalog;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Native Minecraft screen for configuring track pools and track selection multipliers.
 * Modifications are transactional: Done persists and applies the draft, Esc discards.
 */
public final class TrackWeightScreen extends Screen {

    public static final int TAB_HEIGHT = 24;
    public static final int TOOLBAR_HEIGHT = 20;
    public static final int ERROR_ROW_HEIGHT = 14;
    public static final int GAP = 8;
    public static final int BROWSER_MIN_WIDTH = 220;
    public static final int MAIN_MIN_WIDTH = 340;

    public record Bounds(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(Bounds other) {
            if (other == null) {
                return false;
            }
            return other.x >= this.x
                    && other.y >= this.y
                    && other.right() <= this.right()
                    && other.bottom() <= this.bottom();
        }

        public boolean overlaps(Bounds other) {
            if (other == null) {
                return false;
            }
            return this.x < other.right()
                    && this.right() > other.x
                    && this.y < other.bottom()
                    && this.bottom() > other.y;
        }
    }

    public record ResponsiveLayout(
            Bounds workspace,
            Bounds tabs,
            Bounds content,
            Bounds browser,
            Bounds main,
            Bounds wheel,
            Bounds editor,
            Bounds preview,
            Bounds bottomToolbar,
            boolean browserFits,
            boolean browserOpen) {}

    public static final class BrowserState {
        private boolean open;
        private Boolean lastFits;

        public BrowserState() {
            this.open = true;
            this.lastFits = null;
        }

        public BrowserState(boolean open) {
            this.open = open;
            this.lastFits = null;
        }

        public boolean isOpen() {
            return open;
        }

        public void setOpen(boolean open) {
            this.open = open;
        }

        public void toggle() {
            this.open = !this.open;
        }

        public void updateForFit(boolean browserFits) {
            if (lastFits == null) {
                this.open = browserFits;
            } else if (lastFits && !browserFits) {
                this.open = false;
            } else if (!lastFits && browserFits) {
                this.open = true;
            }
            this.lastFits = browserFits;
        }
    }

    private record MainBounds(Bounds wheel, Bounds editor, Bounds preview) {}

    private final MusicPlayerScreen parent;
    private final TrackWeightConfig saved;
    private final Model model;
    private final PreviewState previewState;
    private final TabManager tabManager;
    private final Map<Tab, Pool> tabToPool = new LinkedHashMap<>();
    private final BrowserState browserState = new BrowserState();
    private ResponsiveLayout layout;
    private ScrollablePoolTabBar tabNavigationBar;
    private PinnedMusicInstance currentPreviewInstance;
    private Component errorMessage;

    private Button browserToggleButton;
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
    private Button antiRepeatButton;
    private Button testRollButton;
    private Button jsonButton;
    private Button doneButton;
    private RadialWeightWidget radialWheel;

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
        super(Minecraft.getInstance(), Minecraft.getInstance() != null ? Minecraft.getInstance().font : null, Component.literal("Configure Track Pools"));
        this.parent = parent;
        this.saved = saved != null ? saved : TrackWeightConfig.defaults();
        this.model = new Model(this.saved, pools);
        this.previewState = new PreviewState(
                this::playPreviewSound,
                this::stopPreviewSound,
                () -> MusicDirector.getInstance().pauseForPreview(),
                () -> MusicDirector.getInstance().resumeAfterPreview(true));
        this.tabManager = new TabManager(this::addRenderableWidget, this::removeWidget, this::onTabSelected, tab -> {});
    }

    public static Bounds workspaceBounds(int width, int height) {
        int workspaceWidth = width * 4 / 5;
        int workspaceHeight = height * 4 / 5;
        return new Bounds((width - workspaceWidth) / 2, (height - workspaceHeight) / 2,
                workspaceWidth, workspaceHeight);
    }

    public static ResponsiveLayout responsiveLayout(int width, int height, boolean browserOpen) {
        Bounds workspace = workspaceBounds(width, height);
        Bounds tabs = new Bounds(workspace.x(), workspace.y(), workspace.width(), TAB_HEIGHT);
        Bounds bottomToolbar = new Bounds(workspace.x(), workspace.bottom() - TOOLBAR_HEIGHT, workspace.width(), TOOLBAR_HEIGHT);

        int contentY = tabs.bottom() + GAP;
        int contentBottom = bottomToolbar.y() - ERROR_ROW_HEIGHT;
        int contentHeight = Math.max(0, contentBottom - contentY);
        Bounds content = new Bounds(workspace.x(), contentY, workspace.width(), contentHeight);

        boolean browserFits = content.width() >= BROWSER_MIN_WIDTH + GAP + MAIN_MIN_WIDTH;

        Bounds browser = null;
        Bounds main = null;
        Bounds wheel = null;
        Bounds editor = null;
        Bounds preview = null;

        if (browserOpen) {
            if (browserFits) {
                int browserWidth = Math.clamp(Math.round(content.width() * 0.3f), BROWSER_MIN_WIDTH, 300);
                browser = new Bounds(content.x(), content.y(), browserWidth, content.height());
                int mainX = browser.right() + GAP;
                int mainWidth = content.right() - mainX;
                main = new Bounds(mainX, content.y(), mainWidth, content.height());

                MainBounds mb = computeMainBounds(main);
                wheel = mb.wheel();
                editor = mb.editor();
                preview = mb.preview();
            } else {
                browser = content;
            }
        } else {
            main = content;
            MainBounds mb = computeMainBounds(main);
            wheel = mb.wheel();
            editor = mb.editor();
            preview = mb.preview();
        }

        return new ResponsiveLayout(workspace, tabs, content, browser, main, wheel, editor, preview, bottomToolbar, browserFits, browserOpen);
    }

    private static MainBounds computeMainBounds(Bounds main) {
        int gapWheelEditor = 4;
        int gapEditorPreview = 3;
        int editorHeight = 60;
        int previewHeight = 18;
        int totalBelowWheel = gapWheelEditor + editorHeight + gapEditorPreview + previewHeight;

        int availableWheel = main.height() - totalBelowWheel;
        int maxWheel = Math.min(main.width() - 20, 180);
        int wheelSize = Math.clamp(Math.min(availableWheel, maxWheel), 16, 180);

        int wheelX = main.x() + Math.max(0, (main.width() - wheelSize) / 2);
        int totalHeight = wheelSize + totalBelowWheel;
        int extraY = Math.max(0, main.height() - totalHeight);
        int wheelY = main.y() + extraY / 4;

        int editorY = wheelY + wheelSize + gapWheelEditor;
        int editorWidth = Math.min(main.width(), 200);
        int editorX = main.x() + Math.max(0, (main.width() - editorWidth) / 2);

        int previewY = editorY + editorHeight + gapEditorPreview;
        int previewWidth = Math.min(main.width(), 120);
        int previewX = main.x() + Math.max(0, (main.width() - previewWidth) / 2);

        Bounds wheel = new Bounds(wheelX, wheelY, wheelSize, wheelSize);
        Bounds editor = new Bounds(editorX, editorY, editorWidth, editorHeight);
        Bounds preview = new Bounds(previewX, previewY, previewWidth, previewHeight);

        return new MainBounds(wheel, editor, preview);
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

    public static String poolDisplayName(String poolId) {
        if (poolId == null || poolId.isEmpty()) {
            return "";
        }
        String namespace;
        String path;
        int colon = poolId.indexOf(':');
        if (colon >= 0) {
            namespace = poolId.substring(0, colon);
            path = poolId.substring(colon + 1);
        } else {
            namespace = "minecraft";
            path = poolId;
        }

        if (namespace.equals("minecraft")) {
            if (path.equals("music.creative") || path.equals("creative")) {
                return "Creative";
            }
            if (path.equals("music.credits") || path.equals("credits")) {
                return "Credits";
            }
            if (path.equals("music.dragon") || path.equals("dragon")) {
                return "Ender Dragon";
            }
            if (path.equals("music.end") || path.equals("end")) {
                return "The End";
            }
            if (path.equals("music.game") || path.equals("game")) {
                return "Survival";
            }
            if (path.equals("music.menu") || path.equals("menu")) {
                return "Main Menu";
            }
            if (path.equals("music.under_water") || path.equals("music.underwater")
                    || path.equals("under_water") || path.equals("underwater")) {
                return "Underwater";
            }
            if (path.equals("music.nether") || path.equals("nether")) {
                return "Nether";
            }
            if (path.equals("music.overworld") || path.equals("overworld")) {
                return "Overworld";
            }

            if (path.startsWith("music.overworld.")) {
                String place = path.substring("music.overworld.".length());
                return titleCase(place);
            }

            if (path.startsWith("music.nether.")) {
                String place = path.substring("music.nether.".length());
                return titleCase(place);
            }

            String subPath = path;
            if (subPath.startsWith("music.")) {
                subPath = subPath.substring("music.".length());
            } else if (subPath.startsWith("music/")) {
                subPath = subPath.substring("music/".length());
            }
            return titleCase(subPath);
        }

        String subPath = path;
        if (subPath.startsWith("music.")) {
            subPath = subPath.substring("music.".length());
        } else if (subPath.startsWith("music/")) {
            subPath = subPath.substring("music/".length());
        }
        return namespace + ": " + titleCase(subPath);
    }

    public static String poolTooltip(String poolId) {
        if (poolId == null || poolId.isEmpty()) {
            return "";
        }
        String namespace;
        String path;
        int colon = poolId.indexOf(':');
        if (colon >= 0) {
            namespace = poolId.substring(0, colon);
            path = poolId.substring(colon + 1);
        } else {
            namespace = "minecraft";
            path = poolId;
        }

        if (namespace.equals("minecraft")) {
            if (path.equals("music.creative") || path.equals("creative")) {
                return "Plays in Creative mode";
            }
            if (path.equals("music.credits") || path.equals("credits")) {
                return "Plays during the end credits";
            }
            if (path.equals("music.dragon") || path.equals("dragon")) {
                return "Plays during the Ender Dragon fight";
            }
            if (path.equals("music.end") || path.equals("end")) {
                return "Plays in The End";
            }
            if (path.equals("music.game") || path.equals("game")) {
                return "Plays in Survival mode";
            }
            if (path.equals("music.menu") || path.equals("menu")) {
                return "Plays on the main menu";
            }
            if (path.equals("music.under_water") || path.equals("music.underwater")
                    || path.equals("under_water") || path.equals("underwater")) {
                return "Plays while underwater";
            }
            if (path.equals("music.nether") || path.equals("nether")) {
                return "Plays in the Nether";
            }
            if (path.equals("music.overworld") || path.equals("overworld")) {
                return "Plays in the Overworld";
            }

            if (path.startsWith("music.overworld.")) {
                String place = path.substring("music.overworld.".length());
                return "Plays in the " + titleCase(place) + " Overworld biome";
            }

            if (path.startsWith("music.nether.")) {
                String place = path.substring("music.nether.".length());
                return "Plays in the " + titleCase(place) + " Nether biome";
            }
        }

        return "Custom music pool: " + poolId;
    }

    static String titleCase(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '_' || c == '.' || c == '/' || c == '-') {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                capitalizeNext = true;
            } else if (Character.isWhitespace(c)) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    public static int calculateTabWidth(Font font, String poolId) {
        if (poolId == null || poolId.isEmpty()) {
            return 40;
        }
        String label = poolDisplayName(poolId);
        int textWidth = font != null ? font.width(label) : (label.length() * 6);
        return Math.max(40, textWidth + 16);
    }

    public static int calculateTabWidth(String poolId) {
        return calculateTabWidth(null, poolId);
    }

    public static int calculateRevealOffset(int currentOffset, int tabLeft, int tabWidth, int viewportWidth, int maxScroll) {
        if (maxScroll <= 0) {
            return 0;
        }
        int tabRight = tabLeft + tabWidth;
        int target = currentOffset;
        if (tabWidth >= viewportWidth || tabLeft < currentOffset) {
            target = tabLeft;
        } else if (tabRight > currentOffset + viewportWidth) {
            target = tabRight - viewportWidth;
        }
        return Math.clamp(target, 0, maxScroll);
    }

    void selectTrack(String resourceId) {
        Track prev = model.selectedTrack();
        if (model.selectTrack(resourceId)) {
            if (prev == null || !prev.resourceId().equals(resourceId)) {
                stopPreview();
            }
            updateSelectedTrackWidgets();
        }
    }

    private void onTrackSelectionChanged() {
        stopPreview();
        updateSelectedTrackWidgets();
    }

    private void onDraftChanged() {
        if (trackList != null) {
            trackList.refreshEntries();
        }
        updateSelectedTrackWidgets();
        updateAntiRepeatButton();
    }

    private void updateAntiRepeatButton() {
        if (antiRepeatButton != null) {
            String text = "Anti-Repeat: " + (model.draft().antiRepeat() ? "On" : "Off");
            antiRepeatButton.setTooltip(Tooltip.create(Component.literal(text)));
        }
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
        updatePreviewButton();
        updateWheelModel();
    }

    private void updatePreviewButton() {
        if (previewButton != null) {
            Track track = model.selectedTrack();
            previewButton.active = track != null
                    && track.occurrences() != null
                    && !track.occurrences().isEmpty();
            boolean isPlayingSelected = previewState != null
                    && previewState.isPlaying()
                    && track != null
                    && previewState.playingTrack() != null
                    && track.resourceId().equals(previewState.playingTrack().resourceId());
            previewButton.setMessage(Component.literal(isPlayingSelected ? "Stop Sound" : "Play Sound"));
        }
    }

    private void playPreviewSound(Track track) {
        if (minecraft == null || model.selectedPool() == null || track == null) {
            return;
        }
        if (track.occurrences() == null || track.occurrences().isEmpty()) {
            return;
        }
        Occurrence occurrence = track.occurrences().getFirst();
        if (occurrence.sound() == null) {
            return;
        }
        PinnedMusicInstance preview = new PinnedMusicInstance(
                Identifier.parse(model.selectedPool().id()),
                occurrence.sound(),
                RandomSource.create(0x435545L),
                0L,
                0.0);
        this.currentPreviewInstance = preview;
        if (minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(preview);
        }
    }

    private void stopPreviewSound(Track track) {
        if (currentPreviewInstance != null) {
            if (minecraft != null && minecraft.getSoundManager() != null) {
                minecraft.getSoundManager().stop(currentPreviewInstance);
            }
            currentPreviewInstance = null;
        }
    }

    void stopPreview() {
        if (previewState != null) {
            previewState.stop();
        }
        updatePreviewButton();
    }

    private void updateWheelModel() {
        if (radialWheel != null && model.selectedPool() != null && model.selectedPool().tracks() != null) {
            List<RadialWeightWidget.Slice> slices = new java.util.ArrayList<>();
            for (Track track : model.selectedPool().tracks()) {
                double chance = model.currentChances().getOrDefault(track.resourceId(), 0.0);
                int color = RadialWeightWidget.stableColor(track.resourceId());
                String label = track.title() != null ? track.title() : track.resourceId();
                slices.add(new RadialWeightWidget.Slice(track.resourceId(), label, chance, color));
            }
            String selectedId = model.selectedTrack() != null ? model.selectedTrack().resourceId() : null;
            radialWheel.setModel(slices, selectedId);
        } else if (radialWheel != null) {
            radialWheel.setModel(List.of(), null);
        }
    }

    private void setQuickMultiplier(double multiplier) {
        if (model.setMultiplier(multiplier)) {
            onDraftChanged();
        }
    }

    private void onTabSelected(Tab tab) {
        if (this.tabNavigationBar != null) {
            this.tabNavigationBar.revealTab(tab);
        }
        Pool pool = tabToPool.get(tab);
        if (pool != null && pool != model.selectedPool()) {
            onPoolChanged(pool);
        }
    }

    private void onPoolChanged(Pool newPool) {
        stopPreview();
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
        stopPreview();
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
    public void removed() {
        super.removed();
        stopPreview();
        if (radialWheel != null) {
            radialWheel.close();
        }
    }

    @Override
    public void onClose() {
        stopPreview();
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public void tick() {
        super.tick();
        boolean active = currentPreviewInstance != null
                && minecraft != null
                && minecraft.getSoundManager() != null
                && minecraft.getSoundManager().isActive(currentPreviewInstance);
        if (previewState != null) {
            previewState.tick(active);
        }
        updatePreviewButton();
    }

    @Override
    protected void init() {
        super.init();

        if (radialWheel != null) {
            radialWheel.close();
            radialWheel = null;
        }

        Bounds workspace = workspaceBounds(this.width, this.height);

        if (!model.pools().isEmpty()) {
            tabToPool.clear();
            int navHeight = TAB_HEIGHT;
            int previousOffset = (this.tabNavigationBar != null) ? this.tabNavigationBar.scrollOffset() : 0;
            this.tabNavigationBar = ScrollablePoolTabBar.create(
                    this.tabManager,
                    model.pools(),
                    this.tabToPool,
                    this.font,
                    workspace.x(),
                    workspace.y(),
                    workspace.width(),
                    navHeight);
            addRenderableWidget(this.tabNavigationBar);

            int selectedIndex = model.pools().indexOf(model.selectedPool());
            int indexToSelect = selectedIndex >= 0 ? selectedIndex : 0;
            this.tabNavigationBar.selectTab(indexToSelect, false);
            if (previousOffset > 0) {
                this.tabNavigationBar.setScrollOffset(previousOffset);
            }
            this.tabNavigationBar.revealTab(indexToSelect);
        } else {
            this.tabNavigationBar = null;
        }

        boolean fits = responsiveLayout(this.width, this.height, true).browserFits();
        this.browserState.updateForFit(fits);

        this.layout = responsiveLayout(this.width, this.height, this.browserState.isOpen());

        if (this.tabNavigationBar != null) {
            ScreenRectangle tabArea = new ScreenRectangle(
                    layout.content().x(),
                    layout.content().y(),
                    layout.content().width(),
                    layout.content().height());
            this.tabManager.setTabArea(tabArea);
        }

        initLayoutWidgets();

        refreshTrackList();
        updateSelectedTrackWidgets();
    }

    private void initLayoutWidgets() {
        if (layout.browser() != null) {
            Bounds b = layout.browser();
            int toggleX = b.right() - 20;
            int toggleY = b.y();
            Component toggleMsg = Component.literal("×");
            net.minecraft.network.chat.MutableComponent toggleNarration = Component.literal("Hide track list");
            browserToggleButton = Button.builder(toggleMsg, btn -> toggleBrowser())
                    .bounds(toggleX, toggleY, 20, 20)
                    .tooltip(Tooltip.create(toggleNarration))
                    .createNarration(supplier -> toggleNarration)
                    .build();
            addRenderableWidget(browserToggleButton);

            int searchW = Math.max(40, b.width() - 24);
            searchBox = new EditBox(font, b.x(), b.y(), searchW, 20, Component.literal("Search"));
            searchBox.setHint(Component.literal("Search track or composer…"));
            searchBox.setValue(model.searchQuery());
            searchBox.setResponder(query -> {
                Track prev = model.selectedTrack();
                model.setSearchQuery(query);
                Track current = model.selectedTrack();
                if (prev != current && (prev == null || current == null || !prev.resourceId().equals(current.resourceId()))) {
                    stopPreview();
                }
                refreshTrackList();
                updateSelectedTrackWidgets();
            });
            addRenderableWidget(searchBox);

            int listY = b.y() + 24;
            int listH = Math.max(20, b.bottom() - listY);
            trackList = new TrackList(minecraft, b.width(), listH, listY, 20, model, this::onTrackSelectionChanged);
            trackList.setX(b.x());
            addRenderableWidget(trackList);
        } else {
            searchBox = null;
            trackList = null;

            int toggleX = layout.content().x();
            int toggleY = layout.content().y();
            Component toggleMsg = Component.literal("☰");
            net.minecraft.network.chat.MutableComponent toggleNarration = Component.literal("Show track list");
            browserToggleButton = Button.builder(toggleMsg, btn -> toggleBrowser())
                    .bounds(toggleX, toggleY, 20, 20)
                    .tooltip(Tooltip.create(toggleNarration))
                    .createNarration(supplier -> toggleNarration)
                    .build();
            addRenderableWidget(browserToggleButton);
        }

        if (layout.main() != null) {
            Bounds wheel = layout.wheel();
            radialWheel = new RadialWeightWidget(wheel.x(), wheel.y(), wheel.width(), this::selectTrack);
            addRenderableWidget(radialWheel);

            Bounds editor = layout.editor();
            weightSlider = new WeightSlider(editor.x(), editor.y() + 22, editor.width(), 18, model, this::onDraftChanged);
            addRenderableWidget(weightSlider);

            int qbY = editor.y() + 42;
            int qbW = (editor.width() - 16) / 5;
            btn0x = Button.builder(Component.literal("0×"), b -> setQuickMultiplier(0.0)).bounds(editor.x(), qbY, qbW, 18).build();
            btn05x = Button.builder(Component.literal("0.5×"), b -> setQuickMultiplier(0.5)).bounds(editor.x() + (qbW + 4), qbY, qbW, 18).build();
            btn1x = Button.builder(Component.literal("1×"), b -> setQuickMultiplier(1.0)).bounds(editor.x() + (qbW + 4) * 2, qbY, qbW, 18).build();
            btn2x = Button.builder(Component.literal("2×"), b -> setQuickMultiplier(2.0)).bounds(editor.x() + (qbW + 4) * 3, qbY, qbW, 18).build();
            btn5x = Button.builder(Component.literal("5×"), b -> setQuickMultiplier(5.0)).bounds(editor.x() + (qbW + 4) * 4, qbY, qbW, 18).build();
            addRenderableWidget(btn0x);
            addRenderableWidget(btn05x);
            addRenderableWidget(btn1x);
            addRenderableWidget(btn2x);
            addRenderableWidget(btn5x);

            Bounds preview = layout.preview();
            previewButton = Button.builder(Component.literal("Play Sound"), b -> {
                Track track = model.selectedTrack();
                if (track != null) {
                    previewState.toggle(track);
                    updatePreviewButton();
                }
            }).bounds(preview.x(), preview.y(), preview.width(), preview.height()).build();
            previewButton.active = model.selectedTrack() != null
                    && model.selectedTrack().occurrences() != null
                    && !model.selectedTrack().occurrences().isEmpty();
            addRenderableWidget(previewButton);
        } else {
            radialWheel = null;
            weightSlider = null;
            btn0x = null;
            btn05x = null;
            btn1x = null;
            btn2x = null;
            btn5x = null;
            previewButton = null;
        }

        Bounds tb = layout.bottomToolbar();
        int bottomY = tb.y();
        int buttonWidth = 20;
        int buttonHeight = 20;
        int gap = 4;
        int totalButtons = 7;
        int totalW = totalButtons * buttonWidth + (totalButtons - 1) * gap;
        int bx = tb.x() + (tb.width() - totalW) / 2;

        allButton = Button.builder(Component.literal("↺"), b -> {
            model.applyAll();
            onDraftChanged();
        })
        .bounds(bx, bottomY, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.literal("Reset pool to native weights")))
        .createNarration(supplier -> Component.literal("Reset pool to native weights"))
        .build();
        bx += buttonWidth + gap;

        c418Button = Button.builder(Component.literal("♫"), b -> {
            model.applyC418();
            onDraftChanged();
        })
        .bounds(bx, bottomY, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.literal("Double C418 tracks")))
        .createNarration(supplier -> Component.literal("Double C418 tracks"))
        .build();
        bx += buttonWidth + gap;

        muteButton = Button.builder(Component.literal("∅"), b -> {
            model.mute();
            onDraftChanged();
        })
        .bounds(bx, bottomY, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.literal("Mute selected pool")))
        .createNarration(supplier -> Component.literal("Mute selected pool"))
        .build();
        bx += buttonWidth + gap;

        String antiRepeatText = "Anti-Repeat: " + (model.draft().antiRepeat() ? "On" : "Off");
        antiRepeatButton = Button.builder(Component.literal("⟳"), b -> {
            model.setAntiRepeat(!model.draft().antiRepeat());
            updateAntiRepeatButton();
            onDraftChanged();
        })
        .bounds(bx, bottomY, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.literal(antiRepeatText)))
        .createNarration(supplier -> Component.literal("Anti-Repeat: " + (model.draft().antiRepeat() ? "On" : "Off")))
        .build();
        antiRepeatButton.setOverrideRenderHighlightedSprite(() -> model.draft().antiRepeat() || antiRepeatButton.isHoveredOrFocused());
        bx += buttonWidth + gap;

        testRollButton = Button.builder(Component.literal("⚄"), b -> {
            if (model.selectedPool() != null) {
                long seed = RandomSource.create().nextLong();
                Optional<Occurrence> roll = testRoll(model.selectedPool(), model.draft(), seed);
                roll.ifPresent(occ -> selectTrack(occ.resourceId()));
            }
        })
        .bounds(bx, bottomY, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.literal("Test weighted selection")))
        .createNarration(supplier -> Component.literal("Test weighted selection"))
        .build();
        bx += buttonWidth + gap;

        jsonButton = Button.builder(Component.literal("{}"), b -> {
            stopPreview();
            if (minecraft != null) {
                minecraft.setScreenAndShow(new JsonScreen(this, model.draft()));
            }
        })
        .bounds(bx, bottomY, buttonWidth, buttonHeight)
        .tooltip(Tooltip.create(Component.literal("View and copy JSON")))
        .createNarration(supplier -> Component.literal("View and copy JSON"))
        .build();
        bx += buttonWidth + gap;

        doneButton = Button.builder(Component.literal("✓"), b -> saveAndClose())
                .bounds(bx, bottomY, buttonWidth, buttonHeight)
                .tooltip(Tooltip.create(Component.literal("Done")))
                .createNarration(supplier -> Component.literal("Done"))
                .build();

        addRenderableWidget(allButton);
        addRenderableWidget(c418Button);
        addRenderableWidget(muteButton);
        addRenderableWidget(antiRepeatButton);
        addRenderableWidget(testRollButton);
        addRenderableWidget(jsonButton);
        addRenderableWidget(doneButton);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.tabNavigationBar != null && this.tabNavigationBar.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.tabNavigationBar != null && this.tabNavigationBar.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor extractor) {
        super.extractMenuBackground(extractor);
        if (this.tabNavigationBar != null) {
            int x = this.tabNavigationBar.getX();
            int y = this.tabNavigationBar.getY();
            int w = this.tabNavigationBar.getWidth();
            int h = this.tabNavigationBar.getHeight();
            extractor.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND, x, y, 0.0F, 0.0F, w, h, 16, 16);
            extractor.blit(RenderPipelines.GUI_TEXTURED, HEADER_SEPARATOR, x, y + h - 2, 0.0F, 0.0F, w, 2, 32, 2);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
        super.extractRenderState(extractor, mouseX, mouseY, tickProgress);

        if (layout != null && layout.editor() != null) {
            Track track = model.selectedTrack();
            if (track != null) {
                double chance = model.currentChances().getOrDefault(track.resourceId(), 0.0);
                Bounds editor = layout.editor();
                String line1 = track.title() + " (" + formatPercent(chance) + "%)";
                extractor.text(font, line1, editor.x(), editor.y(), 0xFFFFFF);
                String comp = track.composer() != null ? track.composer() : track.resourceId();
                extractor.text(font, comp, editor.x(), editor.y() + 10, 0xAAAAAA);
            }
        }

        if (errorMessage != null && layout != null) {
            int errorY = layout.bottomToolbar().y() - 12;
            extractor.centeredText(font, errorMessage, width / 2, errorY, 0xFF5555);
        }
    }

    public ResponsiveLayout responsiveLayout() {
        return layout;
    }

    public BrowserState browserState() {
        return browserState;
    }

    public EditBox searchBox() {
        return searchBox;
    }

    public TrackList trackList() {
        return trackList;
    }

    public Button browserToggleButton() {
        return browserToggleButton;
    }

    public void toggleBrowser() {
        this.browserState.toggle();
        if (this.minecraft != null) {
            rebuildWidgets();
        } else {
            clearWidgets();
            init();
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

    RadialWeightWidget radialWheel() {
        return radialWheel;
    }

    ScrollablePoolTabBar tabNavigationBar() {
        return tabNavigationBar;
    }

    TabManager tabManager() {
        return tabManager;
    }

    PreviewState previewState() {
        return previewState;
    }

    Button previewButton() {
        return previewButton;
    }

    public Button allButton() {
        return allButton;
    }

    public Button c418Button() {
        return c418Button;
    }

    public Button muteButton() {
        return muteButton;
    }

    public Button antiRepeatButton() {
        return antiRepeatButton;
    }

    public Button testRollButton() {
        return testRollButton;
    }

    public Button jsonButton() {
        return jsonButton;
    }

    public Button doneButton() {
        return doneButton;
    }

    void initForDimensions(int width, int height) {
        this.width = width;
        this.height = height;
        clearWidgets();
        init();
    }

    static final class PreviewState implements AutoCloseable {
        private final Consumer<Track> onPlay;
        private final Consumer<Track> onStop;
        private final BooleanSupplier pauseAction;
        private final Runnable resumeAction;

        private Track playingTrack;
        private boolean pausedByPreview;

        PreviewState(
                Consumer<Track> onPlay,
                Consumer<Track> onStop,
                BooleanSupplier pauseAction,
                Runnable resumeAction) {
            this.onPlay = onPlay != null ? onPlay : t -> {};
            this.onStop = onStop != null ? onStop : t -> {};
            this.pauseAction = pauseAction != null ? pauseAction : () -> false;
            this.resumeAction = resumeAction != null ? resumeAction : () -> {};
        }

        public boolean isPlaying() {
            return playingTrack != null;
        }

        public Track playingTrack() {
            return playingTrack;
        }

        public boolean ownsPause() {
            return pausedByPreview;
        }

        public boolean pausedByPreview() {
            return pausedByPreview;
        }

        public void toggle(Track track) {
            if (track == null) {
                stop();
                return;
            }
            if (playingTrack != null && playingTrack.resourceId().equals(track.resourceId())) {
                stop();
                return;
            }
            if (playingTrack != null) {
                Track prev = playingTrack;
                playingTrack = null;
                onStop.accept(prev);
            } else if (!pausedByPreview) {
                pausedByPreview = pauseAction.getAsBoolean();
            }
            playingTrack = track;
            onPlay.accept(track);
        }

        public void stop() {
            if (playingTrack != null) {
                Track stopped = playingTrack;
                playingTrack = null;
                onStop.accept(stopped);
            }
            resumeIfNeeded();
        }

        public void tick(boolean soundActive) {
            if (playingTrack != null && !soundActive) {
                Track stopped = playingTrack;
                playingTrack = null;
                onStop.accept(stopped);
                resumeIfNeeded();
            }
        }

        private void resumeIfNeeded() {
            if (pausedByPreview) {
                pausedByPreview = false;
                resumeAction.run();
            }
        }

        @Override
        public void close() {
            stop();
        }
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
        static final int TITLE_COLOR = 0xFFFFFFFF;
        static final int DETAIL_COLOR = 0xFFAAAAAA;

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
                graphics.text(font, track.title(), x + 4, textY, TITLE_COLOR);

                String rightText = formatMultiplier(multiplier) + "×  " + formatPercent(chance) + "%";
                int textWidth = font.width(rightText);
                graphics.text(font, rightText, x + w - textWidth - 6, textY, DETAIL_COLOR);
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

    public static final class ScrollablePoolTabBar extends TabNavigationBar {
        private static final int SCROLL_STEP = 40;

        private final Font font;
        private int scrollOffset;
        private int contentWidth;
        private int viewportX;
        private int viewportWidth;

        public ScrollablePoolTabBar(
                int x,
                int y,
                int width,
                int height,
                TabManager tabManager,
                ImmutableList<TabButton> tabButtons,
                ImmutableList<Tab> tabs,
                Font font) {
            super(x, y, width, height, tabManager, tabButtons, tabs);
            this.font = font;
            this.arrangeElements(width);
        }

        public static ScrollablePoolTabBar create(
                TabManager tabManager,
                List<Pool> pools,
                Map<Tab, Pool> tabToPool,
                Font font,
                int x,
                int y,
                int width,
                int height) {
            ImmutableList.Builder<TabButton> buttonsBuilder = ImmutableList.builder();
            ImmutableList.Builder<Tab> tabsBuilder = ImmutableList.builder();
            for (Pool pool : pools) {
                String displayName = poolDisplayName(pool.id());
                String tooltipText = poolTooltip(pool.id());
                GridLayoutTab tab = new GridLayoutTab(Component.literal(displayName)) {
                    @Override
                    public Component getTabExtraNarration() {
                        return Component.literal(tooltipText);
                    }
                };
                tabToPool.put(tab, pool);
                int btnWidth = calculateTabWidth(font, displayName);
                MenuTabBar.MenuTabButton button = new MenuTabBar.MenuTabButton(tabManager, tab, btnWidth, height);
                buttonsBuilder.add(button);
                tabsBuilder.add(tab);
            }
            ScrollablePoolTabBar bar = new ScrollablePoolTabBar(
                    x, y, width, height, tabManager, buttonsBuilder.build(), tabsBuilder.build(), font);
            for (int i = 0; i < pools.size(); i++) {
                bar.setTabTooltip(i, Tooltip.create(Component.literal(poolTooltip(pools.get(i).id()))));
            }
            return bar;
        }

        public int scrollOffset() {
            return scrollOffset;
        }

        public int contentWidth() {
            return contentWidth;
        }

        public int viewportX() {
            return viewportX;
        }

        public int viewportWidth() {
            return viewportWidth;
        }

        public int maxScroll() {
            return Math.max(0, contentWidth - viewportWidth);
        }

        public ImmutableList<TabButton> tabButtons() {
            return this.tabButtons;
        }

        public void setScrollOffset(int offset) {
            int max = maxScroll();
            this.scrollOffset = Math.clamp(offset, 0, max);
            updatePositions();
        }

        public void scrollBy(int delta) {
            setScrollOffset(this.scrollOffset + delta);
        }

        public void revealTab(int index) {
            if (index < 0 || index >= this.tabButtons.size()) {
                return;
            }
            int tabLeft = 0;
            for (int i = 0; i < index; i++) {
                tabLeft += this.tabButtons.get(i).getWidth();
            }
            int tabWidth = this.tabButtons.get(index).getWidth();
            int newOffset = calculateRevealOffset(this.scrollOffset, tabLeft, tabWidth, this.viewportWidth, maxScroll());
            setScrollOffset(newOffset);
        }

        public void revealTab(Tab tab) {
            int index = this.tabs.indexOf(tab);
            if (index >= 0) {
                revealTab(index);
            }
        }

        public boolean isInsideViewport(double mouseX, double mouseY) {
            return mouseX >= this.viewportX && mouseX < (this.viewportX + this.viewportWidth)
                    && mouseY >= getY() && mouseY <= (getY() + getHeight());
        }

        @Override
        public void arrangeElements(int width) {
            this.width = width;
            this.viewportX = getX();
            this.viewportWidth = width;

            int totalW = 0;
            for (TabButton button : this.tabButtons) {
                int btnW = calculateTabWidth(this.font, button.tab().getTabTitle().getString());
                button.setWidth(btnW);
                button.setHeight(this.height);
                totalW += btnW;
            }
            this.contentWidth = totalW;

            int max = maxScroll();
            this.scrollOffset = Math.clamp(this.scrollOffset, 0, max);

            updatePositions();
        }

        private void updatePositions() {
            int currentX = this.viewportX - this.scrollOffset;
            for (TabButton button : this.tabButtons) {
                button.setX(currentX);
                button.setY(getY());
                currentX += button.getWidth();
            }
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            this.viewportX = x;
            updatePositions();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            updatePositions();
        }

        @Override
        public ScreenRectangle getRectangle() {
            return new ScreenRectangle(getX(), getY(), getWidth(), getHeight());
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= getX() && mouseX < (getX() + getWidth())
                    && mouseY >= getY() && mouseY < (getY() + getHeight());
        }

        @Override
        public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
            if (isInsideViewport(mouseX, mouseY)) {
                for (TabButton button : this.tabButtons) {
                    if (button.isMouseOver(mouseX, mouseY)) {
                        return Optional.of(button);
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.tabButtons;
        }

        @Override
        public void selectTab(int index, boolean playSound) {
            super.selectTab(index, playSound);
            revealTab(index);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (!isMouseOver(mouseX, mouseY)) {
                return false;
            }
            if (maxScroll() <= 0) {
                return false;
            }
            double delta = (scrollX != 0.0) ? scrollX : scrollY;
            if (delta == 0.0) {
                return false;
            }
            int step = (int) Math.round(-Math.signum(delta) * SCROLL_STEP);
            scrollBy(step);
            return true;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
            int viewportRight = this.viewportX + this.viewportWidth;
            boolean insideViewport = isInsideViewport(mouseX, mouseY);
            int tabMouseX = insideViewport ? mouseX : -1;
            int tabMouseY = insideViewport ? mouseY : -1;

            extractor.enableScissor(this.viewportX, getY(), viewportRight, getY() + getHeight());
            try {
                for (TabButton button : this.tabButtons) {
                    if (button.getRight() > this.viewportX && button.getX() < viewportRight) {
                        button.extractRenderState(extractor, tabMouseX, tabMouseY, tickProgress);
                    }
                }
            } finally {
                extractor.disableScissor();
            }
        }
    }
}
