package anpilot.client.minecraft.mixin;

import anpilot.client.features.gui.ANLeaveGuiState;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class ANDisconnectedScreenMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(Screen parent, Component title, Component reason, CallbackInfo ci) {
        if (reason != null) {
            ANLeaveGuiState.INSTANCE.setReason(reason.getString());
        }
    }
}
