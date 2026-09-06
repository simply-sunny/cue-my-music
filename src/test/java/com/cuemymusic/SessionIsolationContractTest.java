package com.cuemymusic;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.MusicPlanner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session isolation contract test: verifies that world JOIN and DISCONNECT
 * boundaries enforce strict title-menu/world music and queue isolation.
 *
 * <p>Requirements verified:
 * <ul>
 *   <li>On JOIN: seed manager RNG, call {@code stopPlaying()} while world
 *       situational context is active, then call {@code beginSession(seed)}.</li>
 *   <li>On DISCONNECT: call {@code stopPlaying()} at boundary, then call
 *       {@code endSession()}.</li>
 *   <li>No custom delay constants are injected, preserving vanilla delay calculations.</li>
 *   <li>Queue/planner state (history, cursor, forward sequence) and transport
 *       are reset across sessions.</li>
 *   <li>Delay RNG seed derives deterministically from the session seed and is
 *       decorrelated from menu/game track selection seeds.</li>
 * </ul>
 */
class SessionIsolationContractTest {

    private static final Path CLIENT_SOURCE =
            Path.of("src/client/java/com/cuemymusic/client/CueMyMusicClient.java");

    @AfterEach
    void resetDirector() {
        MusicDirector.getInstance().beginSession(0L);
    }

    private static String readClientSource() throws IOException {
        assertTrue(Files.exists(CLIENT_SOURCE), "CueMyMusicClient.java must exist at " + CLIENT_SOURCE);
        return Files.readString(CLIENT_SOURCE);
    }

    private static String extractCallbackBody(String source, String eventName) {
        String marker = "ClientPlayConnectionEvents." + eventName + ".register(";
        int startIndex = source.indexOf(marker);
        assertTrue(startIndex >= 0, "Must find " + marker + " in CueMyMusicClient.java");
        int bodyStart = source.indexOf('{', startIndex);
        assertTrue(bodyStart >= 0, "Must find opening brace for " + eventName + " callback");

        // Count balanced braces to extract full callback body
        int depth = 0;
        int bodyEnd = -1;
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    bodyEnd = i;
                    break;
                }
            }
        }
        assertTrue(bodyEnd > bodyStart, "Must find balanced closing brace for " + eventName + " callback");
        return source.substring(bodyStart + 1, bodyEnd);
    }

    private static MusicPlanner getPlanner(MusicDirector director) {
        try {
            Field field = MusicDirector.class.getDeclaredField("planner");
            field.setAccessible(true);
            return (MusicPlanner) field.get(director);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to access planner field on MusicDirector", e);
        }
    }

    @Test
    void joinCallbackFollowsApprovedOrderingAndStopsPlayback() throws IOException {
        String source = readClientSource();
        String joinBody = extractCallbackBody(source, "JOIN");

        int seedRngIndex = joinBody.indexOf("seedDelayRandom");
        int stopPlayingIndex = joinBody.indexOf("stopPlaying()");
        int beginSessionIndex = joinBody.indexOf("beginSession");

        assertTrue(stopPlayingIndex >= 0,
                "JOIN callback must explicitly stop outgoing audio via manager.stopPlaying()");
        assertTrue(seedRngIndex >= 0,
                "JOIN callback must reseed manager delay RNG via seedDelayRandom");
        assertTrue(beginSessionIndex >= 0,
                "JOIN callback must reset session state via beginSession");

        // Approved minimal ordering on JOIN:
        // 1. Seed manager RNG
        // 2. manager.stopPlaying() while world situational context is active
        // 3. beginSession(seed)
        assertTrue(seedRngIndex < stopPlayingIndex,
                "seedDelayRandom must precede stopPlaying() so vanilla nextSongDelay uses the reseeded RNG");
        assertTrue(stopPlayingIndex < beginSessionIndex,
                "manager.stopPlaying() must precede beginSession() to stop outgoing menu audio before fresh session begins");
    }

    @Test
    void disconnectCallbackStopsPlaybackAndEndsSession() throws IOException {
        String source = readClientSource();
        String disconnectBody = extractCallbackBody(source, "DISCONNECT");

        int stopPlayingIndex = disconnectBody.indexOf("stopPlaying()");
        int endSessionIndex = disconnectBody.indexOf("endSession");

        assertTrue(stopPlayingIndex >= 0,
                "DISCONNECT callback must explicitly stop outgoing audio via manager.stopPlaying()");
        assertTrue(endSessionIndex >= 0,
                "DISCONNECT callback must reset session state via endSession()");

        // Ordering on DISCONNECT: manager.stopPlaying() at boundary, then endSession()
        assertTrue(stopPlayingIndex < endSessionIndex,
                "manager.stopPlaying() must precede endSession() at disconnect boundary");
    }

    @Test
    void callbacksDoNotInjectCustomDelayConstants() throws IOException {
        String source = readClientSource();
        String joinBody = extractCallbackBody(source, "JOIN");
        String disconnectBody = extractCallbackBody(source, "DISCONNECT");

        assertFalse(joinBody.contains("setNextSongDelay"),
                "JOIN callback must not override delay with custom constants; preserve vanilla stopPlaying() calculation");
        assertFalse(disconnectBody.contains("setNextSongDelay"),
                "DISCONNECT callback must not override delay with custom constants; preserve vanilla stopPlaying() calculation");
    }

    @Test
    void sessionBoundaryResetsDirectorQueueAndTransport() {
        MusicDirector director = MusicDirector.getInstance();
        director.beginSession(100L);

        MusicPlanner planner = getPlanner(director);
        planner.nextSequence("minecraft:music.game");
        planner.nextSequence("minecraft:music.game");
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/calm1", 0L));

        long genBefore = director.currentGeneration();

        // World JOIN boundary: beginSession
        director.beginSession(200L);
        assertEquals(0, planner.peekSequence("minecraft:music.game"),
                "beginSession must reset sequence indices");
        assertTrue(planner.historySnapshot().isEmpty(),
                "beginSession must clear history snapshot");
        assertFalse(director.canGoPrevious(),
                "beginSession must invalidate previous queue access");
        assertNotEquals(genBefore, director.currentGeneration(),
                "beginSession must advance transport generation to cancel stale audio operations");

        // Add state again and test DISCONNECT boundary: endSession
        planner.nextSequence("minecraft:music.game");
        planner.record(new MusicPlanner.Entry("minecraft:music.game", "music/game/calm2", 0L));
        long genBeforeEnd = director.currentGeneration();

        director.endSession();
        assertEquals(0, planner.peekSequence("minecraft:music.game"),
                "endSession must reset sequence indices");
        assertTrue(planner.historySnapshot().isEmpty(),
                "endSession must clear history snapshot");
        assertFalse(director.canGoPrevious(),
                "endSession must invalidate previous queue access");
        assertNotEquals(genBeforeEnd, director.currentGeneration(),
                "endSession must advance transport generation to cancel stale audio operations");
    }

    @Test
    void delaySeedIsDecorrelatedFromSelectionSeeds() {
        long sessionSeed = 123456789L;
        long delaySeed = MusicDirector.delaySeed(sessionSeed);

        long menuTrackSeed = MusicPlanner.selectionSeed(sessionSeed, "minecraft:music.menu", 0L);
        long gameTrackSeed = MusicPlanner.selectionSeed(sessionSeed, "minecraft:music.game", 0L);
        long creativeTrackSeed = MusicPlanner.selectionSeed(sessionSeed, "minecraft:music.creative", 0L);

        assertNotEquals(delaySeed, menuTrackSeed, "Delay seed must not collide with menu track seed");
        assertNotEquals(delaySeed, gameTrackSeed, "Delay seed must not collide with game track seed");
        assertNotEquals(delaySeed, creativeTrackSeed, "Delay seed must not collide with creative track seed");
    }
}
