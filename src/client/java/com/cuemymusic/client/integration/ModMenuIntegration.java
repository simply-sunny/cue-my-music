package com.cuemymusic.client.integration;

import com.cuemymusic.client.ui.JukeboxLibraryScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu entrypoint. Registers the Jukebox Library screen as the config screen
 * for Cue My Music so it appears in ModMenu's mods list.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new JukeboxLibraryScreen(parent);
    }
}
