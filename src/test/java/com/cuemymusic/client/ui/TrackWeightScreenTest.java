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
import com.cuemymusic.client.ui.TrackWeightScreen.PreviewState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.components.Button;

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
}
