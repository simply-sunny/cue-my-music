package mix.cuemymusic;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class CueMyMusicClient implements ClientModInitializer {
    public static final String MODID = "cuemymusic";

    private static KeyMapping openSettings;
    private static KeyMapping toggle;

    @Override
    public void onInitializeClient() {
        CueConfig.INSTANCE.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MODID, "keys"));
        openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cuemymusic.open_settings", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cuemymusic.toggle", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

        ClientTickEvents.END_CLIENT_TICK.register(CueMyMusicClient::tick);
    }

    private static void tick(Minecraft client) {
        while (openSettings.consumeClick()) {
            client.gui.setScreen(CueConfigScreen.create(client.gui.screen()));
        }
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
