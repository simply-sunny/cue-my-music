package com.cuemymusic.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicPlanner;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.music.WeightedMusicCatalog;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;
import com.cuemymusic.client.ui.TrackWeightScreen.Bounds;
import com.cuemymusic.client.ui.TrackWeightScreen.PreviewState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.TabButton;

import static org.junit.jupiter.api.Assertions.*;

class TrackWeightScreenTest {

    private static Track track(String resourceId, String title, String composer) {
        Occurrence occ = new Occurrence(resourceId, null, 1.0);
        return new Track(resourceId, title, composer, 1.0, List.of(occ));
    }

    private static Track track(String resourceId) {
        return track(resourceId, resourceId, null);
    }

    private static Pool poolWithC418AndUnknown() {
        Track c418 = track("minecraft:music/game/sweden", "Sweden", "C418");
        Track unknown = track("minecraft:music/game/unknown", "Unknown Song", null);
        Track lena = track("minecraft:music/game/relic", "Relic", "Lena Raine");
        return new Pool("minecraft:music.game", List.of(c418, unknown, lena),
                List.of(c418.occurrences().getFirst(), unknown.occurrences().getFirst(), lena.occurrences().getFirst()));
    }

    private static TrackWeightScreen screenWithPools(int count) {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        return new TrackWeightScreen(null, TrackWeightConfig.defaults(), pools);
    }

    @Test
    void workspaceIsCenteredInnerEightyPercent() {
        assertEquals(new Bounds(100, 60, 800, 480), TrackWeightScreen.workspaceBounds(1000, 600));
        assertEquals(new Bounds(30, 21, 240, 167), TrackWeightScreen.workspaceBounds(300, 209));
    }

    @Test
    void poolTabsFillWorkspaceAndHaveNoArrowButtons() {
        TrackWeightScreen screen = screenWithPools(30);
        screen.width = 1000;
        screen.height = 600;
        screen.init();
        Bounds workspace = TrackWeightScreen.workspaceBounds(1000, 600);
        assertEquals(workspace.x(), screen.tabNavigationBar().getX());
        assertEquals(workspace.width(), screen.tabNavigationBar().getWidth());
        assertTrue(screen.tabNavigationBar().maxScroll() > 0);
        assertTrue(screen.children().stream().noneMatch(child -> child instanceof Button button
                && (button.getMessage().getString().equals("<") || button.getMessage().getString().equals(">"))));
    }

    @Test
    void poolTabsPreserveFullIdsAndClampedWheelScrollingAndReveal() {
        TrackWeightScreen screen = screenWithPools(30);
        screen.width = 1000;
        screen.height = 600;
        screen.init();
        TrackWeightScreen.ScrollablePoolTabBar bar = screen.tabNavigationBar();
        Bounds workspace = TrackWeightScreen.workspaceBounds(1000, 600);

        // Full pool IDs remain tab titles
        for (int i = 0; i < 30; i++) {
            TabButton tabBtn = bar.tabButtons().get(i);
            assertEquals("minecraft:music.pool_" + i, tabBtn.getMessage().getString());
        }

        // Wheel scrolling clamps inside maxScroll()
        int maxScroll = bar.maxScroll();
        assertTrue(maxScroll > 0);

        int initialOffset = bar.scrollOffset();
        screen.mouseScrolled(workspace.x() + 10, workspace.y() + 10, 0.0, -1.0);
        assertTrue(bar.scrollOffset() > initialOffset, "Wheel scroll down must increase scroll offset");

        // Scroll past max clamps to maxScroll
        bar.scrollBy(maxScroll + 1000);
        assertEquals(maxScroll, bar.scrollOffset(), "Scroll offset must clamp to maxScroll()");

        // Scrolling further down stays clamped at maxScroll
        screen.mouseScrolled(workspace.x() + 10, workspace.y() + 10, 0.0, -1.0);
        assertEquals(maxScroll, bar.scrollOffset(), "Wheel scroll down must stay clamped to maxScroll()");

        // Scroll past 0 clamps to 0
        bar.scrollBy(-maxScroll - 1000);
        assertEquals(0, bar.scrollOffset(), "Scroll offset must clamp to 0");

        // Scrolling further up stays clamped at 0
        screen.mouseScrolled(workspace.x() + 10, workspace.y() + 10, 0.0, 1.0);
        assertEquals(0, bar.scrollOffset(), "Wheel scroll up must stay clamped to 0");

        // Selected-tab reveal clamps inside maxScroll()
        bar.revealTab(29);
        assertTrue(bar.scrollOffset() <= maxScroll, "Reveal last tab must clamp inside maxScroll()");
        assertTrue(bar.scrollOffset() > 0, "Reveal last tab must increase offset");

        bar.revealTab(0);
        assertEquals(0, bar.scrollOffset(), "Reveal first tab must clamp to 0");
    }

    @Test void configureButtonIsLongAndBottomCentered() {
        TrackWeightScreen.Bounds bounds = MusicPlayerScreen.configureButtonBounds(692, 423);
        assertEquals(200, bounds.width());
        assertEquals(20, bounds.height());
        assertEquals((692 - 200) / 2, bounds.x());
        assertEquals(423 - 6 - 20, bounds.y());
    }

    @Test void settingsDraftIsIndependentFromSavedConfig() {
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightConfig draft = TrackWeightScreen.updateWeight(saved, "p", "t", 5.0);
        assertEquals(1.0, saved.multiplier("p", "t"));
        assertEquals(5.0, draft.multiplier("p", "t"));
    }

    @Test void wideLayoutThresholdFollowsResponsiveBreakpoint() {
        assertFalse(TrackWeightScreen.usesWideLayout(639));
        assertTrue(TrackWeightScreen.usesWideLayout(640));
        assertTrue(TrackWeightScreen.usesWideLayout(1920));
    }

    @Test void playerScreenConfiguresButtonAndOpensSettingsScreen() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/MusicPlayerScreen.java"));
        assertTrue(source.contains("CONFIGURE_LABEL"), "Must define CONFIGURE_LABEL");
        assertTrue(source.contains("\"⚙ Configure Track Pools…\""), "Configure button label must match exact copy");
        assertTrue(source.contains("CONFIGURE_NARRATION"), "Must define CONFIGURE_NARRATION");
        assertTrue(source.contains("\"Configure track pools and selection chances\""), "Narration must match exact copy");
        assertTrue(source.contains("openTrackSettings"), "Must provide openTrackSettings()");
        assertTrue(source.contains("new TrackWeightScreen(this)"), "openTrackSettings must construct TrackWeightScreen with this as parent");
        assertTrue(source.contains("minecraft.setScreenAndShow("), "openTrackSettings must show settings screen");
    }

    @Test void trackWeightScreenSourceGuaranteesTransactionalLifecycle() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("class TrackWeightScreen extends Screen"),
                "TrackWeightScreen must extend Screen");
        assertTrue(source.contains("parent"), "Must store parent screen");
        assertTrue(source.contains("saved"), "Must store saved config");
        assertTrue(source.contains("draft"), "Must store draft config");
        assertTrue(source.contains("onClose()"), "Must override onClose()");
        assertTrue(source.contains("Could not save cue-my-music.json"),
                "Save failure must set error component");

        // Save sequencing: verify strictly within saveAndClose's try-block before catch
        int saveStart = source.indexOf("void saveAndClose()");
        assertTrue(saveStart >= 0, "Must provide saveAndClose()");
        int catchIndex = source.indexOf("catch (IOException", saveStart);
        assertTrue(catchIndex > saveStart, "saveAndClose must catch IOException");
        String saveSuccessBlock = source.substring(saveStart, catchIndex);
        int saveIndex = saveSuccessBlock.indexOf("MusicDirector.getInstance().saveWeightingConfig(draft)");
        int showParentIndex = saveSuccessBlock.indexOf("minecraft.setScreenAndShow(parent)", saveIndex);
        assertTrue(saveIndex >= 0, "saveAndClose must persist draft to MusicDirector");
        assertTrue(showParentIndex > saveIndex,
                "Save success must return to parent only after saveWeightingConfig returns");

        // Discard: verify onClose returns to parent without saving
        int onCloseStart = source.indexOf("void onClose()");
        assertTrue(onCloseStart >= 0, "Must provide onClose()");
        int onCloseEnd = source.indexOf('}', onCloseStart);
        String onCloseBlock = source.substring(onCloseStart, onCloseEnd);
        assertTrue(onCloseBlock.contains("minecraft.setScreenAndShow(parent)"),
                "onClose must return to parent");
        assertFalse(onCloseBlock.contains("saveWeightingConfig"),
                "onClose must discard without saving");
    }

    @Test void searchMatchesTitleComposerAndResource() {
        List<Track> tracks = List.of(track("minecraft:sounds/music/game/sweden.ogg", "Sweden", "C418"));
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "swed").size());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "c418").size());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "music/game").size());
        assertTrue(TrackWeightScreen.filterTracks(tracks, "raine").isEmpty());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, "").size());
        assertEquals(1, TrackWeightScreen.filterTracks(tracks, null).size());
    }

    @Test void sliderEndpointsAndHalfSteps() {
        assertEquals(0.0, TrackWeightScreen.sliderToMultiplier(0.0), 1e-9);
        assertEquals(0.5, TrackWeightScreen.sliderToMultiplier(0.05), 1e-9);
        assertEquals(1.0, TrackWeightScreen.sliderToMultiplier(0.10), 1e-9);
        assertEquals(2.0, TrackWeightScreen.sliderToMultiplier(0.20), 1e-9);
        assertEquals(2.5, TrackWeightScreen.sliderToMultiplier(0.24), 1e-9);
        assertEquals(2.5, TrackWeightScreen.sliderToMultiplier(0.26), 1e-9);
        assertEquals(5.0, TrackWeightScreen.sliderToMultiplier(0.50), 1e-9);
        assertEquals(10.0, TrackWeightScreen.sliderToMultiplier(1.00), 1e-9);

        assertEquals(0.0, TrackWeightScreen.multiplierToSlider(0.0), 1e-9);
        assertEquals(0.05, TrackWeightScreen.multiplierToSlider(0.5), 1e-9);
        assertEquals(0.10, TrackWeightScreen.multiplierToSlider(1.0), 1e-9);
        assertEquals(0.20, TrackWeightScreen.multiplierToSlider(2.0), 1e-9);
        assertEquals(0.50, TrackWeightScreen.multiplierToSlider(5.0), 1e-9);
        assertEquals(1.00, TrackWeightScreen.multiplierToSlider(10.0), 1e-9);
    }

    @Test void c418ActionChangesOnlyCreditedC418Tracks() {
        TrackWeightConfig draft = TrackWeightConfig.defaults();
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig changed = TrackWeightScreen.applyC418(draft, pool);
        assertEquals(2.0, changed.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/relic"));
    }

    @Test void allActionSetsEveryLoadedTrackToOne() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig draft = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 0.0)
                .withMultiplier(pool.id(), "minecraft:music/game/relic", 5.0)
                .withMultiplier("other:pool", "other:track", 3.0);
        TrackWeightConfig changed = TrackWeightScreen.applyAll(draft, pool);
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(1.0, changed.multiplier(pool.id(), "minecraft:music/game/relic"));
        assertEquals(3.0, changed.multiplier("other:pool", "other:track"));
    }

    @Test void muteActionSetsEveryLoadedTrackToZero() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig draft = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.0)
                .withMultiplier("other:pool", "other:track", 4.0);
        TrackWeightConfig changed = TrackWeightScreen.mute(draft, pool);
        assertEquals(0.0, changed.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(0.0, changed.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(0.0, changed.multiplier(pool.id(), "minecraft:music/game/relic"));
        assertEquals(4.0, changed.multiplier("other:pool", "other:track"));
    }

    @Test void testRollProducesDeterministicSelectionWithoutMutatingConfigOrPlanner() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig config = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.0);
        MusicPlanner planner = new MusicPlanner();
        long initialPlannerSeq = planner.peekSequence(pool.id());

        long testSeed = 987654321L;
        Optional<Occurrence> roll1 = TrackWeightScreen.testRoll(pool, config, testSeed);
        Optional<Occurrence> roll2 = TrackWeightScreen.testRoll(pool, config, testSeed);

        assertTrue(roll1.isPresent());
        assertEquals(roll1.get().resourceId(), roll2.get().resourceId());
        assertEquals(WeightedMusicCatalog.select(pool, config, null, testSeed).get().resourceId(),
                roll1.get().resourceId());

        // Assert input config was not mutated
        assertEquals(2.0, config.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, config.multiplier(pool.id(), "minecraft:music/game/unknown"));
        // Assert planner sequence was not mutated
        assertEquals(initialPlannerSeq, planner.peekSequence(pool.id()));
    }

    @Test void chanceRefreshReflectsDraftChanges() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig initial = TrackWeightConfig.defaults();
        Map<String, Double> initialChances = WeightedMusicCatalog.chances(pool, initial, null);
        assertEquals(1.0 / 3.0, initialChances.get("minecraft:music/game/sweden"), 1e-9);

        TrackWeightConfig doubled = TrackWeightScreen.applyC418(initial, pool);
        Map<String, Double> newChances = WeightedMusicCatalog.chances(pool, doubled, null);
        // Total weight: 2.0 + 1.0 + 1.0 = 4.0 -> sweden chance = 2/4 = 0.5
        assertEquals(0.5, newChances.get("minecraft:music/game/sweden"), 1e-9);
        assertEquals(0.25, newChances.get("minecraft:music/game/unknown"), 1e-9);
        assertEquals(0.25, newChances.get("minecraft:music/game/relic"), 1e-9);
    }

    @Test void completeDraftJsonContainsRetainedAndDraftWeights() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig draft = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.5)
                .withMultiplier("custom:pool", "custom:track", 4.0);
        String json = draft.toJson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertTrue(root.get("antiRepeat").getAsBoolean());
        JsonObject weights = root.getAsJsonObject("weights");
        assertEquals(2.5, weights.getAsJsonObject(pool.id()).get("minecraft:music/game/sweden").getAsDouble());
        assertEquals(4.0, weights.getAsJsonObject("custom:pool").get("custom:track").getAsDouble());
    }

    @Test void narrationFormattingBehavior() {
        assertEquals("Sweden, C418, 2×, 50%",
                TrackWeightScreen.formatNarration("Sweden", "C418", 2.0, 0.50));
        assertEquals("Unknown Song, Unknown composer, 1×, 25%",
                TrackWeightScreen.formatNarration("Unknown Song", null, 1.0, 0.25));
        assertEquals("Relic, Lena Raine, 0.5×, 10%",
                TrackWeightScreen.formatNarration("Relic", "Lena Raine", 0.5, 0.10));
    }

    @Test void modelInitialStateAndAccessors() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 2.0);
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));

        assertEquals(pool, model.selectedPool());
        assertEquals(pool.tracks().getFirst(), model.selectedTrack());
        assertEquals(saved, model.draft());
        assertEquals("", model.searchQuery());
        assertTrue(model.currentChances().containsKey("minecraft:music/game/sweden"));

        TrackWeightScreen.Model emptyModel = new TrackWeightScreen.Model(saved, List.of());
        assertNull(emptyModel.selectedPool());
        assertNull(emptyModel.selectedTrack());
        assertTrue(emptyModel.currentChances().isEmpty());
    }

    @Test void filteredSelectionBehavior() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));

        model.setSearchQuery("unknown");
        assertNotNull(model.selectedTrack());
        assertEquals("Unknown Song", model.selectedTrack().title());
        assertEquals(1, model.filteredTracks().size());

        model.setSearchQuery("nonexistent");
        assertNull(model.selectedTrack());
        assertTrue(model.filteredTracks().isEmpty());

        model.setSearchQuery("");
        assertNotNull(model.selectedTrack());
        assertEquals("Sweden", model.selectedTrack().title());
        assertEquals(3, model.filteredTracks().size());
    }

    @Test void poolSwitchClearsSearchAndSynchronizesEditorWithList() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool1, pool2));

        model.setSearchQuery("relic");
        assertEquals("Relic", model.selectedTrack().title());
        assertEquals(1, model.filteredTracks().size());

        model.switchPool(pool2);
        assertEquals("", model.searchQuery());
        assertEquals(pool2, model.selectedPool());
        assertEquals(netherTrack, model.selectedTrack());
        assertEquals(List.of(netherTrack), model.filteredTracks());
        assertEquals(model.selectedTrack(), model.filteredTracks().getFirst());
    }

    @Test void sliderSynchronizationGuardPreventsReentrantCallbacks() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));
        AtomicInteger callbacks = new AtomicInteger(0);

        TrackWeightScreen.WeightSlider slider =
                new TrackWeightScreen.WeightSlider(0, 0, 200, 20, model, callbacks::incrementAndGet);

        // User changes slider value directly
        slider.applyValue();
        assertEquals(1, callbacks.get(), "User slider update must trigger exactly one draft callback");

        // External model update syncing to slider must not re-trigger applyValue / callback
        model.setMultiplier(5.0);
        slider.syncFromModel(5.0);
        assertEquals(1, callbacks.get(), "syncFromModel must not cause re-entrant draft callbacks");
        assertEquals(TrackWeightScreen.multiplierToSlider(5.0), slider.sliderValue(), 1e-6);
    }

    @Test void jsonScreenValueAndClipboardSemantics() {
        String original = "{\"version\":1,\"antiRepeat\":true}";
        assertEquals(original, TrackWeightScreen.JsonScreen.enforceReadOnly(original, "mutated string"));
        assertEquals("", TrackWeightScreen.JsonScreen.enforceReadOnly(null, "incoming"));

        AtomicReference<String> clipboard = new AtomicReference<>();
        TrackWeightScreen.JsonScreen.copyToClipboard(original, clipboard::set);
        assertEquals(original, clipboard.get());
    }

    @Test void wideLayoutBoundsGuaranteeFitAcrossBoundaryWidths() {
        int[] widths = {640, 679, 680, 800, 1920};
        for (int w : widths) {
            assertTrue(TrackWeightScreen.usesWideLayout(w), "Width " + w + " must use wide layout");
            TrackWeightScreen.Bounds editor = TrackWeightScreen.wideEditorBounds(w, 400);
            TrackWeightScreen.Bounds bottomBar = TrackWeightScreen.wideBottomBarBounds(w, 400);

            assertTrue(editor.x() >= 0, "Editor x must be non-negative at width " + w);
            assertTrue(editor.right() <= w, "Editor right (" + editor.right() + ") must fit within width " + w);
            assertTrue(bottomBar.x() >= 0, "Bottom bar x must be non-negative at width " + w);
            assertTrue(bottomBar.right() <= w, "Bottom bar right (" + bottomBar.right() + ") must fit within width " + w);
        }
    }

    @Test void narrowLayoutGeometryGuaranteesNoOverlapAtSmallDimensions() {
        int[][] dims = {{300, 209}, {427, 254}};
        for (int[] dim : dims) {
            int w = dim[0];
            int h = dim[1];
            assertFalse(TrackWeightScreen.usesWideLayout(w), "Width " + w + " must be narrow layout");
            TrackWeightScreen.NarrowGeometry g = TrackWeightScreen.narrowGeometry(w, h);

            assertEquals(26, g.poolSearch().y(),
                    "poolSearch y must be 26 to clear MenuTabBar at " + w + "x" + h);
            assertEquals(46, g.list().y(),
                    "list y must be 46 at " + w + "x" + h);
            assertTrue(24 < g.poolSearch().y(),
                    "MenuTabBar bottom (24) must be strictly above poolSearch y (" + g.poolSearch().y() + ") at " + w + "x" + h);
            assertTrue(g.poolSearch().bottom() < g.list().y(),
                    "PoolSearch bottom (" + g.poolSearch().bottom() + ") must be strictly above list y (" + g.list().y() + ") at " + w + "x" + h);
            assertTrue(g.list().bottom() < g.editorInfo().y(),
                    "List bottom (" + g.list().bottom() + ") must be above editorInfo y (" + g.editorInfo().y() + ") at " + w + "x" + h);
            assertTrue(g.editorInfo().bottom() < g.slider().y(),
                    "EditorInfo bottom (" + g.editorInfo().bottom() + ") must be above slider y (" + g.slider().y() + ") at " + w + "x" + h);
            assertTrue(g.slider().bottom() < g.quickButtons().y(),
                    "Slider bottom (" + g.slider().bottom() + ") must be above quickButtons y (" + g.quickButtons().y() + ") at " + w + "x" + h);
            assertTrue(g.quickButtons().bottom() < g.errorY(),
                    "Quick buttons bottom (" + g.quickButtons().bottom() + ") must be strictly above error message y (" + g.errorY() + ") at " + w + "x" + h);
            assertTrue(g.errorY() + 9 < g.bottomRow1().y(),
                    "Error message bottom (" + (g.errorY() + 9) + ") must be strictly above bottom row 1 y (" + g.bottomRow1().y() + ") at " + w + "x" + h);
            assertTrue(g.bottomRow1().bottom() < g.bottomRow2().y(),
                    "Bottom row 1 bottom (" + g.bottomRow1().bottom() + ") must be above bottom row 2 y (" + g.bottomRow2().y() + ") at " + w + "x" + h);
            assertTrue(g.bottomRow2().bottom() <= h,
                    "Bottom row 2 bottom (" + g.bottomRow2().bottom() + ") must fit within screen height " + h);
        }
    }

    @Test void listNavigationUpdatesSelectedTrackWithoutRecursion() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen.Model model = new TrackWeightScreen.Model(saved, List.of(pool));
        AtomicInteger selectionCallbacks = new AtomicInteger(0);

        TrackWeightScreen.TrackList list =
                new TrackWeightScreen.TrackList(null, 200, 200, 0, 20, model, selectionCallbacks::incrementAndGet);
        list.populate(pool.tracks());

        Track firstTrack = pool.tracks().getFirst();
        assertEquals(firstTrack, model.selectedTrack());

        Track secondTrack = pool.tracks().get(1);
        list.selectTrackEntry(secondTrack.resourceId());

        assertEquals(secondTrack, model.selectedTrack(), "selectTrackEntry must update model selected track");
        assertEquals(1, selectionCallbacks.get(), "Updating track selection must invoke callback once without recursion");

        Track thirdTrack = pool.tracks().get(2);
        TrackWeightScreen.TrackEntry thirdEntry = list.children().get(2);
        // Simulate native keyboard/controller arrow navigation directly calling setSelected
        list.setSelected(thirdEntry);
        assertEquals(thirdTrack, model.selectedTrack(), "Native keyboard navigation via setSelected must update model selected track");
        assertEquals(2, selectionCallbacks.get(), "Keyboard navigation must trigger exactly one additional callback");

        // Selecting already selected track should not trigger recursive callbacks
        list.selectTrackEntry(thirdTrack.resourceId());
        assertEquals(2, selectionCallbacks.get(), "Selecting already selected track must be a no-op");
    }

    @Test void radialWheelAppearsOnlyInWideLayout() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen wideScreen = new TrackWeightScreen(null, saved, pool);
        wideScreen.initForDimensions(800, 400);
        assertNotNull(wideScreen.radialWheel(), "Wheel must appear in wide layout");
        assertEquals(wideScreen.radialWheel().getWidth(), wideScreen.radialWheel().getHeight());

        TrackWeightScreen narrowScreen = new TrackWeightScreen(null, saved, pool);
        narrowScreen.initForDimensions(400, 300);
        assertNull(narrowScreen.radialWheel(), "Wheel must not appear in narrow layout");
    }

    @Test void wideWheelBoundsGuaranteesNoOverlapAcrossBoundaryWidths() {
        int[] widths = {640, 679, 680, 800, 1920};
        int[] heights = {100, 114, 150, 250, 400, 1080};
        for (int w : widths) {
            for (int h : heights) {
                TrackWeightScreen.Bounds list = TrackWeightScreen.wideListBounds(w, h);
                TrackWeightScreen.Bounds editor = TrackWeightScreen.wideEditorBounds(w, h);
                TrackWeightScreen.Bounds bottomBar = TrackWeightScreen.wideBottomBarBounds(w, h);
                TrackWeightScreen.Bounds wheel = TrackWeightScreen.wideWheelBounds(w, h);

                assertTrue(wheel.x() >= list.right(),
                        "Wheel left (" + wheel.x() + ") must be >= list right (" + list.right() + ") at " + w + "x" + h);
                assertTrue(wheel.right() <= editor.x(),
                        "Wheel right (" + wheel.right() + ") must be <= editor left (" + editor.x() + ") at " + w + "x" + h);
                assertTrue(wheel.y() >= 50,
                        "Wheel top (" + wheel.y() + ") must be >= top boundary 50 at " + w + "x" + h);
                assertTrue(wheel.bottom() <= bottomBar.y(),
                        "Wheel bottom (" + wheel.bottom() + ") must be <= bottom bar y (" + bottomBar.y() + ") at " + w + "x" + h);
            }
        }
    }

    @Test void wheelModelSynchronizesWithCatalogChancesAndPreservesOrder() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool);
        screen.initForDimensions(800, 400);

        RadialWeightWidget wheel = screen.radialWheel();
        assertNotNull(wheel);
        List<RadialWeightWidget.Slice> slices = wheel.slices();
        assertEquals(3, slices.size(), "Wheel must receive all tracks in the pool");

        // Preserves track-list order
        assertEquals(pool.tracks().get(0).resourceId(), slices.get(0).resourceId());
        assertEquals(pool.tracks().get(1).resourceId(), slices.get(1).resourceId());
        assertEquals(pool.tracks().get(2).resourceId(), slices.get(2).resourceId());

        // Normalized chances
        assertEquals(1.0 / 3.0, slices.get(0).chance(), 1e-6);
        assertEquals(1.0 / 3.0, slices.get(1).chance(), 1e-6);
        assertEquals(1.0 / 3.0, slices.get(2).chance(), 1e-6);

        // Initial selected track
        assertEquals(pool.tracks().getFirst().resourceId(), wheel.selectedResourceId());

        // Searching tracks in the search box does NOT change the wheel slices (search-independent)
        screen.model().setSearchQuery("relic");
        screen.initForDimensions(800, 400);
        assertEquals(3, screen.radialWheel().slices().size(), "Wheel slices must remain search-independent");
        assertEquals("minecraft:music/game/relic", screen.radialWheel().selectedResourceId(),
                "Search selecting new track must update wheel selected resource ID");

        // Changing track selection via selectTrack updates wheel highlight
        screen.selectTrack("minecraft:music/game/sweden");
        assertEquals("minecraft:music/game/sweden", screen.radialWheel().selectedResourceId());

        // Selecting track updates model and wheel
        screen.selectTrack("minecraft:music/game/unknown");
        assertEquals("Unknown Song", screen.model().selectedTrack().title());
        assertEquals("minecraft:music/game/unknown", screen.radialWheel().selectedResourceId());

        // Draft mutation (applyAll 1x, mute 0x) updates wheel chances
        screen.model().mute();
        screen.selectTrack("minecraft:music/game/unknown");
        for (RadialWeightWidget.Slice s : screen.radialWheel().slices()) {
            assertEquals(0.0, s.chance(), 1e-6, "Muted pool must result in 0 chance for all wheel slices");
        }
    }

    @Test void previewStopsPreviousAndResumesOnlyOwnedPause() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return true; },
                () -> calls.add("resume"));
        state.toggle(track("a"));
        state.toggle(track("b"));
        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a", "play:b", "stop:b", "resume"), calls);
    }

    @Test void previewSecondPressStopsAndResumes() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return true; },
                () -> calls.add("resume"));
        Track trackA = track("a");
        state.toggle(trackA);
        assertTrue(state.isPlaying());
        assertEquals(trackA, state.playingTrack());
        assertEquals(List.of("pause", "play:a"), calls);

        state.toggle(trackA);
        assertFalse(state.isPlaying());
        assertNull(state.playingTrack());
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);

        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);
    }

    @Test void naturalInactiveDetectionResumesOnce() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return true; },
                () -> calls.add("resume"));
        state.toggle(track("a"));
        assertTrue(state.isPlaying());
        assertEquals(List.of("pause", "play:a"), calls);

        state.tick(true);
        assertTrue(state.isPlaying());
        assertEquals(List.of("pause", "play:a"), calls);

        state.tick(false);
        assertFalse(state.isPlaying());
        assertNull(state.playingTrack());
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);

        state.tick(false);
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);

        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a", "resume"), calls);
    }

    @Test void previewWithNoCurrentMusicDoesNotIssueResume() {
        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> { calls.add("pause"); return false; },
                () -> calls.add("resume"));
        state.toggle(track("a"));
        assertTrue(state.isPlaying());
        assertFalse(state.ownsPause());
        assertEquals(List.of("pause", "play:a"), calls);

        state.toggle(track("a"));
        assertFalse(state.isPlaying());
        assertEquals(List.of("pause", "play:a", "stop:a"), calls);

        state.close();
        assertEquals(List.of("pause", "play:a", "stop:a"), calls);
    }

    @Test void previewOperationsDoNotMutateConfigOrPlannerSequence() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig config = TrackWeightConfig.defaults()
                .withMultiplier(pool.id(), "minecraft:music/game/sweden", 3.0);
        MusicPlanner planner = new MusicPlanner();
        long initialSeq = planner.peekSequence(pool.id());

        List<String> calls = new ArrayList<>();
        PreviewState state = new PreviewState(
                sound -> calls.add("play:" + sound.resourceId()),
                sound -> calls.add("stop:" + sound.resourceId()),
                () -> true,
                () -> {});

        Track sweden = pool.tracks().getFirst();
        state.toggle(sweden);
        state.tick(true);
        state.toggle(pool.tracks().get(1));
        state.close();

        assertEquals(3.0, config.multiplier(pool.id(), "minecraft:music/game/sweden"));
        assertEquals(1.0, config.multiplier(pool.id(), "minecraft:music/game/unknown"));
        assertEquals(initialSeq, planner.peekSequence(pool.id()));
    }

    @Test void previewButtonLabelAndLifecycleInScreen() {
        Pool pool = poolWithC418AndUnknown();
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pool);
        screen.initForDimensions(800, 400);

        Button previewBtn = screen.previewButton();
        assertNotNull(previewBtn);
        assertTrue(previewBtn.active);
        assertEquals("Play Sound", previewBtn.getMessage().getString());

        // Pressing preview button starts preview and updates label to "Stop Sound"
        previewBtn.onPress(null);
        assertTrue(screen.previewState().isPlaying());
        assertEquals("Stop Sound", previewBtn.getMessage().getString());

        // Second press stops preview and resets label to "Play Sound"
        previewBtn.onPress(null);
        assertFalse(screen.previewState().isPlaying());
        assertEquals("Play Sound", previewBtn.getMessage().getString());

        // Start preview again
        previewBtn.onPress(null);
        assertTrue(screen.previewState().isPlaying());

        // Selecting a different track stops preview and resets button label
        screen.selectTrack("minecraft:music/game/unknown");
        assertFalse(screen.previewState().isPlaying());
        assertEquals("Play Sound", previewBtn.getMessage().getString());

        // Start preview again
        previewBtn.onPress(null);
        assertTrue(screen.previewState().isPlaying());

        // Screen removal stops preview
        screen.removed();
        assertFalse(screen.previewState().isPlaying());
        assertEquals("Play Sound", previewBtn.getMessage().getString());

        // Start preview again
        previewBtn.onPress(null);
        assertTrue(screen.previewState().isPlaying());

        // Screen onClose (Escape) stops preview
        screen.onClose();
        assertFalse(screen.previewState().isPlaying());
        assertEquals("Play Sound", previewBtn.getMessage().getString());
    }

    @Test
    void trackEntryTextColorsAreOpaqueArgb() {
        assertEquals(0xFF, (TrackWeightScreen.TrackEntry.TITLE_COLOR >>> 24) & 0xFF, "Title color must have full alpha");
        assertEquals(0xFF, (TrackWeightScreen.TrackEntry.DETAIL_COLOR >>> 24) & 0xFF, "Detail color must have full alpha");
    }

    @Test
    void nativeMenuTabBarExactApiContract() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("TabNavigationBar"), "Must use TabNavigationBar");
        assertTrue(source.contains("MenuTabBar.MenuTabButton"), "Must use native MenuTabBar.MenuTabButton");
        assertTrue(source.contains("TabManager"), "Must use TabManager");
        assertTrue(source.contains("GridLayoutTab"), "Must use GridLayoutTab");
        assertTrue(source.contains("tabNavigationBar.keyPressed"), "Must delegate keyPressed to tabNavigationBar");
        assertTrue(source.contains("CreateWorldScreen.TAB_HEADER_BACKGROUND"), "Must use CreateWorldScreen.TAB_HEADER_BACKGROUND");
        assertTrue(source.contains("HEADER_SEPARATOR"), "Must draw HEADER_SEPARATOR");
        assertTrue(source.contains("extractMenuBackground"), "Must override extractMenuBackground for split background");
        assertTrue(source.contains("RenderPipelines.GUI_TEXTURED"), "Must use RenderPipelines.GUI_TEXTURED for header background blit");
        assertFalse(source.contains("CycleButton<Pool>"), "Must not use CycleButton<Pool>");
    }

    @Test
    void nativeMenuTabBarCreatedInBothWideAndNarrowLayoutsWithDynamicPools() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen wideScreen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        wideScreen.initForDimensions(800, 400);

        assertNotNull(wideScreen.tabNavigationBar(), "Tab bar must exist in wide layout");
        assertEquals(2, wideScreen.tabNavigationBar().getTabs().size(), "Tab bar must have one tab per pool");
        assertEquals("minecraft:music.game", wideScreen.tabNavigationBar().getTabs().get(0).getTabTitle().getString());
        assertEquals("minecraft:music.nether", wideScreen.tabNavigationBar().getTabs().get(1).getTabTitle().getString());
        assertEquals(pool1, wideScreen.selectedPool(), "First pool must be selected initially");

        // Verify no CycleButton for Pool exists in wide layout
        boolean hasPoolCycleButtonWide = wideScreen.children().stream()
                .filter(w -> w instanceof net.minecraft.client.gui.components.CycleButton)
                .map(w -> ((net.minecraft.client.gui.components.CycleButton<?>) w).getMessage().getString())
                .anyMatch(msg -> msg.contains("Pool") || msg.contains(pool1.id()));
        assertFalse(hasPoolCycleButtonWide, "Wide layout must not contain a CycleButton for pool selection");

        TrackWeightScreen narrowScreen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        narrowScreen.initForDimensions(400, 300);

        assertNotNull(narrowScreen.tabNavigationBar(), "Tab bar must exist in narrow layout");
        assertEquals(2, narrowScreen.tabNavigationBar().getTabs().size(), "Tab bar must have one tab per pool");
        assertEquals("minecraft:music.game", narrowScreen.tabNavigationBar().getTabs().get(0).getTabTitle().getString());
        assertEquals("minecraft:music.nether", narrowScreen.tabNavigationBar().getTabs().get(1).getTabTitle().getString());

        // Verify no CycleButton for Pool exists in narrow layout
        boolean hasPoolCycleButtonNarrow = narrowScreen.children().stream()
                .filter(w -> w instanceof net.minecraft.client.gui.components.CycleButton)
                .map(w -> ((net.minecraft.client.gui.components.CycleButton<?>) w).getMessage().getString())
                .anyMatch(msg -> msg.contains("Pool") || msg.contains(pool1.id()));
        assertFalse(hasPoolCycleButtonNarrow, "Narrow layout must not contain a CycleButton for pool selection");
    }

    @Test
    void emptyPoolsYieldsNoTabBar() {
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen wideScreen = new TrackWeightScreen(null, saved, List.of());
        wideScreen.initForDimensions(800, 400);
        assertNull(wideScreen.tabNavigationBar(), "Empty pools must yield no tab bar in wide layout");
        assertNull(wideScreen.selectedPool());

        TrackWeightScreen narrowScreen = new TrackWeightScreen(null, saved, List.of());
        narrowScreen.initForDimensions(400, 300);
        assertNull(narrowScreen.tabNavigationBar(), "Empty pools must yield no tab bar in narrow layout");
        assertNull(narrowScreen.selectedPool());
    }

    @Test
    void tabSelectionRoutesThroughSinglePathSwitchingPoolAndPreservingState() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        screen.initForDimensions(800, 400);

        screen.model().setSearchQuery("swed");
        assertEquals("Sweden", screen.model().selectedTrack().title());

        screen.previewState().toggle(screen.model().selectedTrack());
        assertTrue(screen.previewState().isPlaying(), "Preview should be playing before pool switch");

        // Select tab 1
        screen.tabNavigationBar().selectTab(1, false);

        assertEquals(pool2, screen.selectedPool(), "Selected pool must update to pool 2");
        assertEquals(netherTrack, screen.selectedTrack(), "Selected track must update to pool 2's first track");
        assertFalse(screen.previewState().isPlaying(), "Preview must be stopped on pool switch");
        assertEquals("", screen.model().searchQuery(), "Search query must be cleared on pool switch");
        assertEquals(1, screen.radialWheel().slices().size(), "Radial wheel must be synced to pool 2");
        assertEquals(netherTrack.resourceId(), screen.radialWheel().selectedResourceId());
    }

    @Test
    void resizePreservesSelectedPoolAndDoesNotFireUnintendedReset() {
        Pool pool1 = poolWithC418AndUnknown();
        Track nether1 = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Track nether2 = track("minecraft:music/nether/chrysopoeia", "Chrysopoeia", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(nether1, nether2),
                List.of(nether1.occurrences().getFirst(), nether2.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        screen.initForDimensions(800, 400);

        // Switch to pool 2
        screen.tabNavigationBar().selectTab(1, false);
        assertEquals(pool2, screen.selectedPool());

        // Select nether2 specifically
        screen.selectTrack(nether2.resourceId());
        assertEquals(nether2, screen.selectedTrack());

        // Start preview on nether2
        screen.previewState().toggle(nether2);
        assertTrue(screen.previewState().isPlaying());

        // Simulate resize to narrow layout
        screen.initForDimensions(400, 300);

        assertEquals(pool2, screen.selectedPool(), "Selected pool must be preserved across resize");
        assertNotNull(screen.tabNavigationBar());
        assertEquals("minecraft:music.nether", screen.tabManager().getCurrentTab().getTabTitle().getString(),
                "Tab manager must have pool 2's tab selected after resize");
        assertEquals(nether2, screen.selectedTrack(), "Selected track within pool must not reset to first track on resize");
        assertTrue(screen.previewState().isPlaying(), "Active preview must not be stopped on resize");
    }

    @Test
    void fullNamesInTabTitlesAndButtons() {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        Track customTrack = track("custom_mod:sound/ambient", "Cave", "Composer");
        Pool pool3 = new Pool("custom_mod:ambient.cave", List.of(customTrack),
                List.of(customTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2, pool3));
        screen.initForDimensions(800, 400);

        TrackWeightScreen.ScrollablePoolTabBar tabBar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertNotNull(tabBar);
        assertEquals(3, tabBar.getTabs().size());

        assertEquals("minecraft:music.game", tabBar.getTabs().get(0).getTabTitle().getString());
        assertEquals("minecraft:music.nether", tabBar.getTabs().get(1).getTabTitle().getString());
        assertEquals("custom_mod:ambient.cave", tabBar.getTabs().get(2).getTabTitle().getString());

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = tabBar.children();
        assertEquals(3, children.size(), "Tab bar should contain only tab buttons, no arrow buttons");
        assertTrue(children.stream().noneMatch(c -> c instanceof Button b && (b.getMessage().getString().equals("<") || b.getMessage().getString().equals(">"))));

        net.minecraft.client.gui.components.AbstractWidget btn0 = (net.minecraft.client.gui.components.AbstractWidget) children.get(0);
        net.minecraft.client.gui.components.AbstractWidget btn1 = (net.minecraft.client.gui.components.AbstractWidget) children.get(1);
        net.minecraft.client.gui.components.AbstractWidget btn2 = (net.minecraft.client.gui.components.AbstractWidget) children.get(2);

        assertEquals("minecraft:music.game", btn0.getMessage().getString());
        assertEquals("minecraft:music.nether", btn1.getMessage().getString());
        assertEquals("custom_mod:ambient.cave", btn2.getMessage().getString());
    }

    @Test
    void calculateTabWidthDerivedFromFontMetricsAndPaddingWithoutTruncation() {
        // Fallback font metrics (null font): text length * 6 + 16, min 40
        assertEquals(Math.max(40, "minecraft:music.game".length() * 6 + 16),
                TrackWeightScreen.calculateTabWidth("minecraft:music.game"));
        assertEquals(Math.max(40, "credits".length() * 6 + 16),
                TrackWeightScreen.calculateTabWidth("credits"));
        assertEquals(40, TrackWeightScreen.calculateTabWidth(""));
        assertEquals(40, TrackWeightScreen.calculateTabWidth(null));

        // Derived width must accommodate full string without truncation
        int widthGame = TrackWeightScreen.calculateTabWidth("minecraft:music.game");
        assertTrue(widthGame >= "minecraft:music.game".length() * 6 + 16);
    }

    @Test
    void contentWidthAndOffsetClamping() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertNotNull(bar);

        int expectedContentW = 0;
        for (Pool p : pools) {
            expectedContentW += TrackWeightScreen.calculateTabWidth(p.id());
        }
        assertEquals(expectedContentW, bar.contentWidth(), "Content width must match sum of tab button widths");

        Bounds workspace = TrackWeightScreen.workspaceBounds(600, 400);
        assertEquals(workspace.width(), bar.viewportWidth());
        int expectedMaxScroll = Math.max(0, expectedContentW - workspace.width());
        assertEquals(expectedMaxScroll, bar.maxScroll());

        // Clamping below 0
        bar.setScrollOffset(-100);
        assertEquals(0, bar.scrollOffset());

        // Clamping above max
        bar.setScrollOffset(expectedMaxScroll + 500);
        assertEquals(expectedMaxScroll, bar.scrollOffset());

        // Setting within range
        bar.setScrollOffset(100);
        assertEquals(100, bar.scrollOffset());
    }

    @Test
    void wheelDirectionScrollsHorizontally() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        bar.setScrollOffset(100);

        int tabY = bar.getY() + 12;
        int tabX = bar.getX() + 50;

        // Vertical wheel down (scrollY < 0) scrolls content right (increases offset)
        int before = bar.scrollOffset();
        boolean handled = screen.mouseScrolled(tabX, tabY, 0.0, -1.0);
        assertTrue(handled, "Mouse scroll over tab bar must be handled");
        assertTrue(bar.scrollOffset() > before, "Wheel down must increase scroll offset (scroll content right)");

        // Vertical wheel up (scrollY > 0) scrolls content left (decreases offset)
        before = bar.scrollOffset();
        handled = screen.mouseScrolled(tabX, tabY, 0.0, 1.0);
        assertTrue(handled);
        assertTrue(bar.scrollOffset() < before, "Wheel up must decrease scroll offset (scroll content left)");

        // Horizontal wheel right (scrollX < 0) increases offset
        before = bar.scrollOffset();
        handled = screen.mouseScrolled(tabX, tabY, -1.0, 0.0);
        assertTrue(handled);
        assertTrue(bar.scrollOffset() > before, "Horizontal scroll right must increase scroll offset");

        // Horizontal wheel left (scrollX > 0) decreases offset
        before = bar.scrollOffset();
        handled = screen.mouseScrolled(tabX, tabY, 1.0, 0.0);
        assertTrue(handled);
        assertTrue(bar.scrollOffset() < before, "Horizontal scroll left must decrease scroll offset");

        // Wheel outside bar (e.g. y = 10) must not scroll bar
        before = bar.scrollOffset();
        bar.mouseScrolled(tabX, 10, 0.0, 1.0);
        assertEquals(before, bar.scrollOffset(), "Mouse scroll outside bar bounds must not alter offset");
    }

    @Test
    void calculateRevealOffsetPureBoundsBehavior() {
        int viewportW = 200;
        int maxScroll = 500;

        // 1. Tab already fully in view [100, 160] with offset=50, visible=[50, 250]
        assertEquals(50, TrackWeightScreen.calculateRevealOffset(50, 100, 60, viewportW, maxScroll));

        // 2. Tab to right of viewport: tab [300, 360] with offset=50 (visible=[50, 250])
        // Target offset should align right edge: 360 - 200 = 160
        assertEquals(160, TrackWeightScreen.calculateRevealOffset(50, 300, 60, viewportW, maxScroll));

        // 3. Tab to left of viewport: tab [20, 80] with offset=100 (visible=[100, 300])
        // Target offset should align left edge: 20
        assertEquals(20, TrackWeightScreen.calculateRevealOffset(100, 20, 60, viewportW, maxScroll));

        // 4. Tab wider than viewport: tab [100, 350] (width 250 > 200) with offset=0
        // Target offset should align left edge: 100
        assertEquals(100, TrackWeightScreen.calculateRevealOffset(0, 100, 250, viewportW, maxScroll));

        // 5. Clamping to [0, maxScroll]
        assertEquals(0, TrackWeightScreen.calculateRevealOffset(100, 0, 50, viewportW, maxScroll));
        assertEquals(maxScroll, TrackWeightScreen.calculateRevealOffset(0, 800, 50, viewportW, maxScroll));

        // 6. Max scroll <= 0
        assertEquals(0, TrackWeightScreen.calculateRevealOffset(50, 20, 50, viewportW, 0));
    }

    @Test
    void autoRevealSelectedOnTabSelectionAndKeyboard() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertEquals(0, bar.scrollOffset());

        // Select tab 15 (way off-viewport initially)
        bar.selectTab(15, false);
        assertTrue(bar.scrollOffset() > 0, "Selecting tab 15 must scroll bar to reveal it");

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = bar.children();
        net.minecraft.client.gui.components.AbstractWidget btn15 =
                (net.minecraft.client.gui.components.AbstractWidget) children.get(15);
        assertTrue(btn15.getX() >= bar.viewportX(), "Revealed tab 15 X must be >= viewportX");
        assertTrue(btn15.getRight() <= bar.viewportX() + bar.viewportWidth(), "Revealed tab 15 right must be <= viewport right");

        // Select tab 0: must scroll back to start
        bar.selectTab(0, false);
        assertEquals(0, bar.scrollOffset(), "Selecting tab 0 must scroll back to 0");
        net.minecraft.client.gui.components.AbstractWidget btn0 =
                (net.minecraft.client.gui.components.AbstractWidget) children.get(0);
        assertEquals(bar.viewportX(), btn0.getX(), "Tab 0 must be aligned to viewportX");
    }

    @Test
    void resizingClampsScrollOffsetAndKeepsSelectedVisible() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(1728, 1080);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        // Select pool 20
        bar.selectTab(20, false);
        Pool selected = screen.selectedPool();
        assertEquals("minecraft:music.pool_20", selected.id());

        // Resize to 800 width
        screen.initForDimensions(800, 600);
        TrackWeightScreen.ScrollablePoolTabBar bar800 = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertEquals(selected, screen.selectedPool(), "Selected pool preserved on resize");
        assertTrue(bar800.scrollOffset() <= bar800.maxScroll(), "Offset clamped to new maxScroll");

        net.minecraft.client.gui.components.AbstractWidget btn20 =
                (net.minecraft.client.gui.components.AbstractWidget) bar800.children().get(20);
        assertTrue(btn20.getX() >= bar800.viewportX(), "Selected tab must remain visible in viewport after resize");
        assertTrue(btn20.getRight() <= bar800.viewportX() + bar800.viewportWidth());

        // Resize to 400 narrow
        screen.initForDimensions(400, 300);
        TrackWeightScreen.ScrollablePoolTabBar bar400 = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertEquals(selected, screen.selectedPool(), "Selected pool preserved on narrow resize");
        assertTrue(bar400.scrollOffset() <= bar400.maxScroll());
    }

    @Test
    void thirtyPoolsNoOverlapAndContentExceedsViewport() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(800, 600);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        assertNotNull(bar);
        assertTrue(bar.contentWidth() > bar.viewportWidth(), "Content width must exceed 800px viewport");

        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children = bar.children();
        assertEquals(30, children.size(), "30 tabs = 30 children without arrow buttons");

        // Verify adjacency: button[i].right == button[i+1].x for all tabs
        for (int i = 0; i < 29; i++) {
            net.minecraft.client.gui.components.AbstractWidget curr = (net.minecraft.client.gui.components.AbstractWidget) children.get(i);
            net.minecraft.client.gui.components.AbstractWidget next = (net.minecraft.client.gui.components.AbstractWidget) children.get(i + 1);
            assertEquals(curr.getRight(), next.getX(), "Adjacent tab buttons must touch with zero overlap and zero gap at tab " + i);
            assertEquals(24, curr.getHeight());
            assertEquals(bar.getY(), curr.getY());
        }
    }

    @Test
    void offViewportButtonsCannotReceiveMouseClicks() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Track t = track("minecraft:music/test" + i, "Test " + i, "Composer " + i);
            pools.add(new Pool("minecraft:music.pool_" + i, List.of(t), List.of(t.occurrences().getFirst())));
        }
        TrackWeightConfig saved = TrackWeightConfig.defaults();
        TrackWeightScreen screen = new TrackWeightScreen(null, saved, pools);
        screen.initForDimensions(600, 400);

        TrackWeightScreen.ScrollablePoolTabBar bar = (TrackWeightScreen.ScrollablePoolTabBar) screen.tabNavigationBar();
        bar.setScrollOffset(0);

        // Tab button 0 is in viewport (x = viewportX..): mouse click inside visible tab
        Optional<net.minecraft.client.gui.components.events.GuiEventListener> child =
                bar.getChildAt(bar.viewportX() + 10, bar.getY() + 10);
        assertTrue(child.isPresent());
        assertEquals(bar.children().get(0), child.get(), "Mouse over visible tab must return that tab button");

        // Mouse click to the left of the workspace tab bar (e.g. x = bar.viewportX() - 10) must return empty
        child = bar.getChildAt(bar.viewportX() - 10, bar.getY() + 10);
        assertTrue(child.isEmpty(), "Clicks left of viewport must not hit tab buttons");

        // Now scroll to 200: button 0 is off to the left (x < bar.viewportX())
        bar.setScrollOffset(200);
        child = bar.getChildAt(bar.viewportX() - 10, bar.getY() + 10);
        assertTrue(child.isEmpty(), "Clicks left of viewport must not hit scrolled off tab buttons");

        // Mouse click outside bar vertically (e.g. y = bar.getY() - 10 or y = bar.getY() + 30) must return empty
        child = bar.getChildAt(bar.viewportX() + 10, bar.getY() - 10);
        assertTrue(child.isEmpty());
        child = bar.getChildAt(bar.viewportX() + 10, bar.getY() + 30);
        assertTrue(child.isEmpty());
    }

    @Test
    void everyTabButtonReceivesFullPoolIdTooltip() throws Exception {
        Pool pool1 = poolWithC418AndUnknown();
        Track netherTrack = track("minecraft:music/nether/rubedo", "Rubedo", "Lena Raine");
        Pool pool2 = new Pool("minecraft:music.nether", List.of(netherTrack),
                List.of(netherTrack.occurrences().getFirst()));
        TrackWeightConfig saved = TrackWeightConfig.defaults();

        TrackWeightScreen screen = new TrackWeightScreen(null, saved, List.of(pool1, pool2));
        screen.initForDimensions(800, 400);

        java.lang.reflect.Field tooltipField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
        tooltipField.setAccessible(true);

        List<? extends net.minecraft.client.gui.components.TabButton> buttons =
                screen.tabNavigationBar().tabButtons();
        assertEquals(2, buttons.size());

        for (int i = 0; i < buttons.size(); i++) {
            net.minecraft.client.gui.components.AbstractWidget btn = (net.minecraft.client.gui.components.AbstractWidget) buttons.get(i);
            net.minecraft.client.gui.components.WidgetTooltipHolder holder =
                    (net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(btn);
            assertNotNull(holder, "WidgetTooltipHolder must exist on tab button " + i);
            net.minecraft.client.gui.components.Tooltip tooltip = holder.get();
            assertNotNull(tooltip, "Tooltip must be set on tab button " + i);

            java.lang.reflect.Field messageField = net.minecraft.client.gui.components.Tooltip.class.getDeclaredField("message");
            messageField.setAccessible(true);
            net.minecraft.network.chat.Component msg = (net.minecraft.network.chat.Component) messageField.get(tooltip);
            assertEquals(i == 0 ? pool1.id() : pool2.id(), msg.getString(),
                    "Tooltip message must match full pool ID for tab " + i);
        }
    }
}
