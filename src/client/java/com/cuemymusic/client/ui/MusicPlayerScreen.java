package com.cuemymusic.client.ui;

import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

/** Centered transport opened through Mod Menu. */
public final class MusicPlayerScreen extends Screen {
    private final Screen modMenuScreen;
    private final Screen optionsScreen;

    public MusicPlayerScreen(Screen modMenuScreen, Screen optionsScreen) {
        super(Component.literal("Cue My Music"));
        this.modMenuScreen = modMenuScreen;
        this.optionsScreen = optionsScreen;
    }

    public static MusicPlayerScreen fromModMenu(Screen modMenuScreen) {
        Minecraft client = Minecraft.getInstance();
        Screen lastScreen = client.level == null ? new TitleScreen() : new PauseScreen(true);
        return new MusicPlayerScreen(modMenuScreen,
                optionsTarget(lastScreen, client.options, client.level != null));
    }

    static boolean requiresOptionsTarget(Class<?> screenClass) {
        return screenClass != OptionsScreen.class;
    }

    static Screen optionsTarget(Screen screen, Options options, boolean inWorld) {
        return requiresOptionsTarget(screen.getClass()) ? new OptionsScreen(screen, options, inWorld) : screen;
    }

    static void openFromVanillaScreen(Minecraft client, Screen screen) {
        Screen optionsScreen = optionsTarget(screen, client.options, client.level != null);
        client.setScreenAndShow(new MusicPlayerScreen(ModMenuApi.createModsScreen(optionsScreen), optionsScreen));
    }

    void openOptions() {
        minecraft.setScreenAndShow(optionsScreen);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(modMenuScreen);
    }
}
