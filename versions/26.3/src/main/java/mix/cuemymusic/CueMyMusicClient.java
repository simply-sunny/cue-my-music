package mix.cuemymusic;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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

        // Media keys go in Miscellaneous. On a Mac keyboard F7, F8 and F9 are the previous, play/pause and next keys.
        boolean mac = Util.getPlatform() == Util.OS.OSX;
        previous = key("key.cuemymusic.previous", mac ? InputConstants.KEY_F7 : InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC);
        playPause = key("key.cuemymusic.play_pause", mac ? InputConstants.KEY_F8 : InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC);
        next = key("key.cuemymusic.next", mac ? InputConstants.KEY_F9 : InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC);

        ClientTickEvents.END_CLIENT_TICK.register(CueMyMusicClient::tick);
    }

    private static KeyMapping key(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYBOARD, key, category));
    }

    /** Handles the media keys while a screen has the keyboard, since key mappings only fire in game. */
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
