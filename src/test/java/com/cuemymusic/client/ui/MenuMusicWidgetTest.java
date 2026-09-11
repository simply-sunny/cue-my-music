package com.cuemymusic.client.ui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.MusicGraph;
import com.cuemymusic.client.music.MusicPlanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Menu/UI music contract: the Pause-screen transport panel stays clean on
 * both Title-screen and Pause-screen dimensions, and the queue projection
 * plus credit splitting cover the four vanilla menu tracks
 * ({@code music.menu.beginning_2}, {@code music.menu.moog_city_2},
 * {@code music.menu.mutation}, {@code music.menu.floating_trees}).
 *
 * <p>Live Minecraft state (active sound, language, situational music) stays
 * a runtime concern: the headless projection path is verified over a
 * faithful menu pool through the exact calls the director uses
 * ({@code peekSequence} + {@code selectionSeed} +
 * {@code WeighedSoundEvents.getSound}), while the director entry point is
 * pinned to its honest headless contract (empty, never throws).
 */
class MenuMusicWidgetTest {

    /** Vanilla menu files backing the four menu tracks. */
    private static final List<String> MENU_FILES = List.of(
            "music/menu/beginning_2",
            "music/menu/moog_city_2",
            "music/menu/mutation",
            "music/menu/floating_trees");

    /** Native resolved credits for the four menu tracks (artist - title). */
    private static final List<String> MENU_CREDITS = List.of(
            "C418 - Beginning 2",
            "C418 - Moog City 2",
            "C418 - Mutation",
            "C418 - Floating Trees");

    private static final String MENU_EVENT_ID = "minecraft:music.menu";

    /** Representative Title-screen canvas (wide, roomy). */
    private static final int TITLE_WIDTH = 854;
    private static final int TITLE_HEIGHT = 480;
    /** Representative Pause-screen canvas (narrower, centred menu overlay). */
    private static final int PAUSE_WIDTH = 427;
    private static final int PAUSE_HEIGHT = 240;

    private static final int LINE_HEIGHT = 9;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void injectTestListProvider() throws Exception {
        Field provider = MusicGraph.class.getDeclaredField("entriesProvider");
        provider.setAccessible(true);
        provider.set(null, (Function<WeighedSoundEvents, List<Weighted<Sound>>>) event -> {
            try {
                Field list = WeighedSoundEvents.class.getDeclaredField("list");
                list.setAccessible(true);
                return (List<Weighted<Sound>>) list.get(event);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Sound file(String name, int weight) {
        return new Sound(
                Identifier.parse("minecraft:" + name),
                ConstantFloat.of(1.0F),
                ConstantFloat.of(1.0F),
                weight,
                Sound.Type.FILE,
                false,
                false,
                16);
    }

    private static WeighedSoundEvents menuPool() {
        WeighedSoundEvents menu = new WeighedSoundEvents(Identifier.parse(MENU_EVENT_ID), null);
        for (String name : MENU_FILES) {
            menu.addSound(file(name, 1));
        }
        return menu;
    }

    // ---- splitCredit with the four menu tracks ----

    @Test void splitCreditSplitsMenuTracksIntoTitleAndArtist() {
        assertEquals(new MusicDirector.TrackInfo("Beginning 2", "C418"),
                MusicDirector.splitCredit("C418 - Beginning 2"));
        assertEquals(new MusicDirector.TrackInfo("Moog City 2", "C418"),
                MusicDirector.splitCredit("C418 - Moog City 2"));
        assertEquals(new MusicDirector.TrackInfo("Mutation", "C418"),
                MusicDirector.splitCredit("C418 - Mutation"));
        assertEquals(new MusicDirector.TrackInfo("Floating Trees", "C418"),
                MusicDirector.splitCredit("C418 - Floating Trees"));
    }

    @Test void menuCreditsRoundTripThroughDisplayLines() {
        for (String credit : MENU_CREDITS) {
            MusicDirector.TrackInfo info = MusicDirector.splitCredit(credit);
            assertNotNull(info.artist(), "menu credit must carry an artist: " + credit);
            assertEquals(List.of(info.title(), info.artist()),
                    PauseMusicWidget.displayLines(java.util.Optional.of(info)));
        }
    }

    @Test void formatUpcomingNumbersMenuTracks() {
        assertEquals("1. Beginning 2 - C418",
                PauseMusicWidget.formatUpcoming(1, new MusicDirector.TrackInfo("Beginning 2", "C418")));
        assertEquals("2. Moog City 2 - C418",
                PauseMusicWidget.formatUpcoming(2, new MusicDirector.TrackInfo("Moog City 2", "C418")));
        assertEquals("3. Mutation - C418",
                PauseMusicWidget.formatUpcoming(3, new MusicDirector.TrackInfo("Mutation", "C418")));
        assertEquals("4. Floating Trees - C418",
                PauseMusicWidget.formatUpcoming(4, new MusicDirector.TrackInfo("Floating Trees", "C418")));
        assertEquals("5. Floating Trees",
                PauseMusicWidget.formatUpcoming(5, new MusicDirector.TrackInfo("Floating Trees", null)));
    }

    // ---- upcomingTracks(5) projection over the menu pool ----

    /**
     * Mirrors {@link MusicDirector#upcomingTracks(int)} exactly (forward
     * index from {@code peekSequence} plus {@code selectionSeed} plus the
     * real vanilla weighted choice) over a faithful four-file menu pool.
     */
    private static List<String> projectQueue(WeighedSoundEvents menu, long sessionSeed, long baseIndex,
            int count) {
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seed = MusicPlanner.selectionSeed(sessionSeed, MENU_EVENT_ID, baseIndex + i);
            Sound chosen = menu.getSound(RandomSource.create(seed));
            assertNotNull(chosen, "menu pool must select a sound at index " + i);
            assertFalse(MusicGraph.isSilence(chosen), "menu pool must never select silence");
            picked.add(chosen.getPath().toString());
        }
        return List.copyOf(picked);
    }

    @Test void menuQueueProjectionPicksFiveEligibleMenuFiles() {
        WeighedSoundEvents menu = menuPool();
        Set<String> eligible = MusicGraph.eligibleFiles(menu, id -> null, entry -> null);
        assertEquals(4, eligible.size(), "menu pool must expose exactly four files: " + eligible);
        for (String name : MENU_FILES) {
            assertTrue(eligible.stream().anyMatch(path -> path.contains(name)),
                    "eligible set must contain " + name + ", got " + eligible);
        }

        MusicPlanner planner = new MusicPlanner();
        planner.setSessionSeed(20260906L);
        List<String> picked = projectQueue(menu, planner.getSessionSeed(),
                planner.peekSequence(MENU_EVENT_ID), 5);

        assertEquals(5, picked.size(), "upcomingTracks(5) must project exactly five picks");
        for (String path : picked) {
            assertTrue(eligible.contains(path),
                    "every projected pick must be an exact eligible menu member, got " + path);
        }
    }

    @Test void menuQueueProjectionIsDeterministic() {
        WeighedSoundEvents menu = menuPool();
        List<String> first = projectQueue(menu, 20260906L, 0L, 5);
        List<String> second = projectQueue(menu, 20260906L, 0L, 5);
        assertEquals(first, second, "same session seed and index must reproduce the queue");
        List<String> otherSeed = projectQueue(menu, 7L, 0L, 5);
        assertEquals(5, otherSeed.size());
    }

    @Test void upcomingTracksEntryPointStaysHonestHeadless() {
        List<MusicDirector.TrackInfo> upcoming =
                MusicDirector.getInstance().upcomingTracks(5);
        assertNotNull(upcoming, "upcomingTracks must never return null");
        assertTrue(upcoming.size() <= 5, "upcomingTracks(5) projects at most five, got " + upcoming.size());
        if (Minecraft.getInstance() == null) {
            assertTrue(upcoming.isEmpty(),
                    "without a live situational context the projection is honestly empty");
        }
    }

    // ---- multi-screen layout: TitleScreen vs PauseScreen ----

    private static void assertPanelClean(int screenWidth, int screenHeight,
            PauseMusicWidget.PanelLayout layout, boolean queueOpen) {
        // Right-edge margin and top margin honored on every screen.
        assertEquals(screenWidth - PauseMusicWidget.MARGIN,
                layout.boxX() + layout.boxWidth(),
                "panel right edge must honor the margin");
        assertTrue(layout.boxX() >= 0, "panel must stay on-screen");
        assertEquals(PauseMusicWidget.MARGIN, layout.boxY(), "panel must sit at top margin");

        // Transport row: ordered, compact, flexible END, fully inside the box.
        assertEquals(layout.previousX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.playPauseX());
        assertEquals(layout.playPauseX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.nextX());
        assertEquals(layout.nextX() + PauseMusicWidget.BUTTON_SIZE + PauseMusicWidget.GAP,
                layout.endX());
        assertEquals(layout.endX() + layout.endWidth() + PauseMusicWidget.GAP,
                layout.queueX());
        assertTrue(layout.previousX() >= layout.boxX() + PauseMusicWidget.PAD - 2,
                "transport row must start inside the box");
        assertTrue(layout.queueX() + PauseMusicWidget.QUEUE_WIDTH
                <= layout.boxX() + layout.boxWidth() + 2,
                "transport row must end inside the box");
        assertEquals(layout.buttonsY() + PauseMusicWidget.BUTTON_SIZE,
                layout.boxY() + layout.playerCardHeight() - PauseMusicWidget.PAD,
                "transport row must sit at the player card bottom");

        // Scrubber sits to the right of credits, with minimize button at far right.
        assertTrue(layout.sliderX() >= layout.titleX() + layout.titleWidth());
        assertTrue(layout.buttonsY() > layout.sliderY());

        if (queueOpen) {
            assertTrue(layout.queueCardHeight() > 0);
            assertTrue(layout.queueHeaderY() > layout.buttonsY());
            assertTrue(layout.queueListY() > layout.queueHeaderY());
            assertTrue(layout.queueListY() + 5 * (LINE_HEIGHT + PauseMusicWidget.LINE_GAP)
                    <= layout.queueCardY() + layout.queueCardHeight() + 2,
                    "five queue rows must fit inside the queue card");
        }
    }

    @Test void layoutWorksCleanlyOnPauseScreenDimensions() {
        // Longest menu credit ("C418 - Floating Trees" side) at closed and open states.
        PauseMusicWidget.PanelLayout closed =
                PauseMusicWidget.panelLayout(PAUSE_WIDTH, PAUSE_HEIGHT, 90, 30, LINE_HEIGHT, false);
        assertPanelClean(PAUSE_WIDTH, PAUSE_HEIGHT, closed, false);

        PauseMusicWidget.PanelLayout open =
                PauseMusicWidget.panelLayout(PAUSE_WIDTH, PAUSE_HEIGHT, 90, 30, LINE_HEIGHT, true);
        assertPanelClean(PAUSE_WIDTH, PAUSE_HEIGHT, open, true);
        assertTrue(open.boxHeight() > closed.boxHeight(),
                "opening the queue must grow the box: " + closed.boxHeight() + " -> " + open.boxHeight());
    }

    @Test void layoutWorksCleanlyOnOptionsScreenDimensions() {
        PauseMusicWidget.PanelLayout closed =
                PauseMusicWidget.panelLayout(TITLE_WIDTH, TITLE_HEIGHT, 90, 30, LINE_HEIGHT, false);
        assertPanelClean(TITLE_WIDTH, TITLE_HEIGHT, closed, false);

        PauseMusicWidget.PanelLayout open =
                PauseMusicWidget.panelLayout(TITLE_WIDTH, TITLE_HEIGHT, 90, 30, LINE_HEIGHT, true);
        assertPanelClean(TITLE_WIDTH, TITLE_HEIGHT, open, true);
        assertTrue(open.boxHeight() > closed.boxHeight(),
                "opening the queue must grow the box: " + closed.boxHeight() + " -> " + open.boxHeight());
    }

    @Test void narrowAndWideScreensUseResponsiveBoxWidths() {
        PauseMusicWidget.PanelLayout pause =
                PauseMusicWidget.panelLayout(PAUSE_WIDTH, PAUSE_HEIGHT, 60, 40, LINE_HEIGHT, false);
        PauseMusicWidget.PanelLayout options =
                PauseMusicWidget.panelLayout(TITLE_WIDTH, TITLE_HEIGHT, 60, 40, LINE_HEIGHT, false);
        assertEquals(pause.boxHeight(), options.boxHeight());
        assertEquals(PauseMusicWidget.MIN_WIDTH, pause.boxWidth());
        assertEquals(PauseMusicWidget.FIXED_WIDTH, options.boxWidth());
        assertEquals(PAUSE_WIDTH - PauseMusicWidget.MARGIN, pause.boxX() + pause.boxWidth());
        assertEquals(TITLE_WIDTH - PauseMusicWidget.MARGIN, options.boxX() + options.boxWidth());
        assertEquals(PauseMusicWidget.MARGIN, pause.boxY());
        assertEquals(PauseMusicWidget.MARGIN, options.boxY());
    }

    @Test void narrowPauseScreenStillFitsTransportRow() {
        PauseMusicWidget.PanelLayout layout =
                PauseMusicWidget.panelLayout(220, 240, 0, 0, LINE_HEIGHT, false);
        assertTrue(layout.previousX() >= 0, "transport row must stay on-screen");
        assertTrue(layout.boxHeight() <= 72,
                "narrow closed box must stay compact, got " + layout.boxHeight());
    }
}
