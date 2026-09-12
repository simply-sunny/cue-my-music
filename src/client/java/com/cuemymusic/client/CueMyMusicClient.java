package com.cuemymusic.client;

import java.util.concurrent.ThreadLocalRandom;

import com.cuemymusic.client.music.MusicDirector;
import com.cuemymusic.client.music.TrackWeightConfig;
import com.cuemymusic.client.ui.PauseMusicWidget;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CueMyMusicClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Cue My Music] deterministic vanilla music controls");
        PauseMusicWidget.register();
        MusicDirector.getInstance().initializeWeighting(TrackWeightConfig.defaultPath());

        // World-session boundaries (join/disconnect, not dimension transfer):
        // outgoing menu/world audio stops at the boundary, vanilla's delay RNG
        // is reseeded from a session-derived salt, vanilla delay is computed
        // from the active situational context without custom delay constants,
        // and session queue/planner/transport state is reset.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            long sessionSeed = ThreadLocalRandom.current().nextLong();
            if (client.getMusicManager() != null) {
                MusicDirector.getInstance().seedDelayRandom(client.getMusicManager(), sessionSeed);
                client.getMusicManager().stopPlaying();
            }
            MusicDirector.getInstance().beginSession(sessionSeed);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.getMusicManager() != null) {
                client.getMusicManager().stopPlaying();
            }
            MusicDirector.getInstance().endSession();
        });

        // Resource reload: drop the resource-dependent future pin and prune
        // history references against the live graph.
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("cue_my_music", "music_session"),
                new SimpleReloadListener<Void>() {
                    @Override
                    protected Void prepare(PreparableReloadListener.SharedState sharedState) {
                        return null;
                    }

                    @Override
                    protected void apply(Void data, PreparableReloadListener.SharedState sharedState) {
                        MusicDirector director = MusicDirector.getInstance();
                        director.onResourcesReloaded();
                        Minecraft client = Minecraft.getInstance();
                        if (client != null) {
                            client.execute(() -> {
                                SoundManager sounds = client.getSoundManager();
                                if (sounds != null) {
                                    director.reloadWeightedCatalog(sounds);
                                }
                            });
                        }
                    }
                });
    }
}
