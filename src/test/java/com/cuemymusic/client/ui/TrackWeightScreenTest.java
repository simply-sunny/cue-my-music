package com.cuemymusic.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.TrackWeightConfig;

import static org.junit.jupiter.api.Assertions.*;

class TrackWeightScreenTest {

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
}
