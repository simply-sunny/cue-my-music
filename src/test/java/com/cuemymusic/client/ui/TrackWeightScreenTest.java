package com.cuemymusic.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicPlanner;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.music.WeightedMusicCatalog;
import com.cuemymusic.client.music.WeightedMusicCatalog.Occurrence;
import com.cuemymusic.client.music.WeightedMusicCatalog.Pool;
import com.cuemymusic.client.music.WeightedMusicCatalog.Track;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;

class TrackWeightScreenTest {

    private static Track track(String resourceId, String title, String composer) {
        Occurrence occ = new Occurrence(resourceId, null, 1.0);
        return new Track(resourceId, title, composer, 1.0, List.of(occ));
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

    @Test void screenAccessorsExposeSelectedPoolTrackAndDraft() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("Pool selectedPool()"), "Must provide selectedPool accessor");
        assertTrue(source.contains("Track selectedTrack()"), "Must provide selectedTrack accessor");
        assertTrue(source.contains("TrackWeightConfig draft()"), "Must provide draft accessor");
        assertTrue(source.contains("TrackWeightConfig saved()"), "Must provide saved accessor");
        assertTrue(source.contains("this.selectedPool = this.pools.getFirst()"),
                "Must initialize selectedPool from first catalog pool");
        assertTrue(source.contains("this.selectedTrack = this.selectedPool.tracks().getFirst()"),
                "Must initialize selectedTrack from first track");
    }

    @Test void jsonScreenContractAndSourceStructure() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("class JsonScreen extends Screen"),
                "Must declare static nested JsonScreen extending Screen");
        assertTrue(source.contains("MultiLineEditBox.builder()"),
                "JsonScreen must use MultiLineEditBox.builder()");
        assertTrue(source.contains(".setShowBackground(true)"),
                "JsonScreen must enable background on MultiLineEditBox");
        assertTrue(source.contains("setClipboard"),
                "JsonScreen Copy button must set clipboard");
        assertTrue(source.contains("CommonComponents.GUI_BACK"),
                "JsonScreen must include native Back button");
        assertTrue(source.contains("setValueListener"),
                "JsonScreen must enforce read-only draft JSON content");
    }

    @Test void trackListAndNativeControlsContractAndNarration() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/com/cuemymusic/client/ui/TrackWeightScreen.java"));
        assertTrue(source.contains("class TrackList extends ObjectSelectionList<TrackEntry>"),
                "Must declare TrackList extending ObjectSelectionList<TrackEntry>");
        assertTrue(source.contains("class TrackEntry extends ObjectSelectionList.Entry<TrackEntry>"),
                "Must declare TrackEntry extending ObjectSelectionList.Entry");
        assertTrue(source.contains("class WeightSlider extends AbstractSliderButton"),
                "Must declare WeightSlider extending AbstractSliderButton");

        // Approved quick weight buttons: 0×, 0.5×, 1×, 2×, 5×
        assertTrue(source.contains("\"0×\""), "Must include 0× quick button");
        assertTrue(source.contains("\"0.5×\""), "Must include 0.5× quick button");
        assertTrue(source.contains("\"1×\""), "Must include 1× quick button");
        assertTrue(source.contains("\"2×\""), "Must include 2× quick button");
        assertTrue(source.contains("\"5×\""), "Must include 5× quick button");

        // Pool actions: All 1×, C418 2×, Mute 0×
        assertTrue(source.contains("\"All 1×\""), "Must include All 1× action button");
        assertTrue(source.contains("\"C418 2×\""), "Must include C418 2× action button");
        assertTrue(source.contains("\"Mute 0×\""), "Must include Mute 0× action button");

        // Other controls: Anti-Repeat, Test Roll, JSON, Play Sound
        assertTrue(source.contains("\"Anti-Repeat\""), "Must include Anti-Repeat control");
        assertTrue(source.contains("\"Test Roll\""), "Must include Test Roll button");
        assertTrue(source.contains("\"JSON\""), "Must include JSON button");
        assertTrue(source.contains("\"Play Sound\""), "Must include Play Sound button");
        assertTrue(source.contains("\"Search track or composer…\""),
                "Search edit box must have hint 'Search track or composer…'");

        // Narration includes title, composer, multiplier, and chance
        assertTrue(source.contains("getNarration()"), "TrackEntry must implement getNarration()");

        // Forbidden presets check
        assertFalse(source.contains("namedPreset"), "Named presets are forbidden");
        assertFalse(source.contains("applyPreset"), "Preset methods are forbidden");
    }
}
