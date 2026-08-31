package com.cuemymusic.client.debug;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.client.ui.JukeboxLibraryScreen;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal stub for 26.2 - debug commands disabled for UI build. */
public final class CueMyMusicCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/commands");
    private CueMyMusicCommands() {}
    public static void register() {
        LOGGER.info("[Cue My Music] Debug commands stub registered (UI build)");
        // No-op: Fabric command API disabled for 26.2 compatibility
    }
}
