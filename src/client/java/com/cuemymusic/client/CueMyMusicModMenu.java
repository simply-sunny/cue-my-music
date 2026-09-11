package com.cuemymusic.client;

import com.cuemymusic.client.ui.MusicPlayerScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class CueMyMusicModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MusicPlayerScreen::fromModMenu;
    }
}
