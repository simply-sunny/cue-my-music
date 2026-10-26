package mix.cuemymusic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

public final class CueMyMusicClient implements ClientModInitializer {
    public static final String MODID = "cuemymusic";

    private static KeyMapping openPlayer;
    private static KeyMapping toggle;
    private static KeyMapping playPause;
    private static KeyMapping previous;
    private static KeyMapping next;

    @Override
    public void onInitializeClient() {
        CueConfig.INSTANCE.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MODID, "keys"));
        openPlayer = key("key.cuemymusic.open_player", InputConstants.UNKNOWN.getValue(), category);
        toggle = key("key.cuemymusic.toggle", InputConstants.UNKNOWN.getValue(), category);

        // On a Mac keyboard F7, F8 and F9 are the previous, play/pause and next keys.
        boolean mac = Util.getPlatform() == Util.OS.OSX;
        previous = key("key.cuemymusic.previous", mac ? InputConstants.KEY_F7 : InputConstants.UNKNOWN.getValue(), category);
        playPause = key("key.cuemymusic.play_pause", mac ? InputConstants.KEY_F8 : InputConstants.UNKNOWN.getValue(), category);
        next = key("key.cuemymusic.next", mac ? InputConstants.KEY_F9 : InputConstants.UNKNOWN.getValue(), category);

        ClientTickEvents.END_CLIENT_TICK.register(CueMyMusicClient::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CueCommands.register(dispatcher));
        // Key mappings only fire in game, so the keys are also handled on every other screen.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof CueScreen) return;
            if (screen instanceof TitleScreen) addToIconRow(screen);
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                if (typing(s)) return;
                if (openPlayer.matches(event)) client.gui.setScreen(new CueScreen(s));
                else mediaKey(event);
            });
        });
    }

    /** The music button that opens the player, for the title screen's and pause menu's rows of icons. */
    public static SpriteIconButton playerButton(Screen parent) {
        return SpriteIconButton.builder(Component.translatable("cuemymusic.title"),
                        button -> Minecraft.getInstance().gui.setScreen(new CueScreen(parent)), true)
                .width(20)
                .sprite(Identifier.fromNamespaceAndPath(MODID, "music"), 16, 16)
                .withTootip()
                .build();
    }

    /**
     * Adds the music button to the end of the title screen's row of square icons (friends, language,
     * accessibility, and any other mod's) and centres the row again, the way the title screen spaces it.
     */
    private static void addToIconRow(Screen screen) {
        List<AbstractWidget> buttons = Screens.getWidgets(screen);
        AbstractWidget anchor = buttons.stream()
                .filter(b -> b instanceof SpriteIconButton && b.getWidth() == 20 && b.getHeight() == 20)
                .findFirst().orElse(null);
        if (anchor == null) return;
        int y = anchor.getY();
        List<AbstractWidget> row = new ArrayList<>(buttons.stream()
                .filter(b -> b.getY() == y && b.getWidth() == 20 && b.getHeight() == 20)
                .sorted(Comparator.comparingInt(AbstractWidget::getX))
                .toList());
        SpriteIconButton ours = playerButton(screen);
        buttons.add(ours);
        row.add(ours);
        int x = screen.width / 2 - (row.size() * 24 - 4) / 2;
        for (AbstractWidget b : row) {
            b.setPosition(x, y);
            x += 24;
        }
    }

    /** True while a key press goes somewhere else: into a text box, or into a key being rebound. */
    private static boolean typing(Screen screen) {
        return screen instanceof KeyBindsScreen || screen.getFocused() instanceof EditBox || screen.getFocused() instanceof MultiLineEditBox;
    }

    /** True if {@code event} is the open player key. */
    static boolean openKey(KeyEvent event) {
        return openPlayer.matches(event);
    }

    private static KeyMapping key(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYBOARD, key, category));
    }

    /** Handles previous, play/pause and next while a screen has the keyboard. */
    static boolean mediaKey(KeyEvent event) {
        CuePlayer player = CuePlayer.INSTANCE;
        if (playPause.matches(event)) player.togglePause();
        else if (next.matches(event)) player.next();
        else if (previous.matches(event)) player.previous();
        else return false;
        return true;
    }

    private static void tick(Minecraft client) {
        CuePlayer player = CuePlayer.INSTANCE;
        player.tick();
        while (openPlayer.consumeClick()) {
            client.gui.setScreen(new CueScreen(client.gui.screen()));
        }
        while (playPause.consumeClick()) player.togglePause();
        while (next.consumeClick()) player.next();
        while (previous.consumeClick()) player.previous();
        while (toggle.consumeClick()) {
            CueConfig cfg = CueConfig.INSTANCE;
            cfg.enabled = !cfg.enabled;
            cfg.save();
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable(
                        cfg.enabled ? "cuemymusic.toggle.on" : "cuemymusic.toggle.off"));
            }
        }
    }
}
