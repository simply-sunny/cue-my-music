package mix.cuemymusic.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;

import mix.cuemymusic.CueMyMusicClient;

/** A music button in the pause menu's row of icons, with the notes from the "Now playing" toast in the player's green. */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin {
    @Inject(
            method = "createPauseMenu",
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
    private void cuemymusic$addPlayerButton(CallbackInfo ci, @Local LinearLayout iconButtonRow) {
        iconButtonRow.addChild(CueMyMusicClient.playerButton((PauseScreen) (Object) this));
    }
}
