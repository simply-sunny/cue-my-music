package com.cuemymusic.client;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.client.debug.CueMyMusicCommands;
import com.cuemymusic.client.music.VanillaTrackRegistry;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CueMyMusicClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Cue My Music] Initializing client");
        try {
            var inst = CueMyMusic.getInstance();
            if (inst != null && inst.getLibrary() != null && inst.getLibrary().getAllTracks().isEmpty()) {
                VanillaTrackRegistry.registerAll(inst.getLibrary());
                LOGGER.info("[Cue My Music] Registered vanilla tracks: {}", inst.getLibrary().getAllTracks().size());
            }
        } catch (Exception e) { LOGGER.warn("[Cue My Music] Failed to auto-register vanilla tracks", e); }

        try {
            var inst = CueMyMusic.getInstance();
            boolean enabled = inst == null || inst.getConfig() == null || inst.getConfig().isEnableDebugCommands();
            if (enabled) {
                CueMyMusicCommands.register();
                LOGGER.info("[Cue My Music] Debug commands registered (/cuemymusic, /cmm)");
            }
        } catch (Exception e) { LOGGER.warn("[Cue My Music] Failed to register debug commands", e); }
    }
}
