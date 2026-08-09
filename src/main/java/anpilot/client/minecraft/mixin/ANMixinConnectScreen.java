package anpilot.client.minecraft.mixin;

import anpilot.client.bootstrap.ANServiceRegistry;
import anpilot.client.features.event.impl.ServerConnectBeginEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ANMixinConnectScreen {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void tryConnectEvent(Screen screen, Minecraft client, ServerAddress address, ServerData info, boolean quickPlay, CallbackInfo ci) {
        if (ANServiceRegistry.INSTANCE.isInitialized()) {
            ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new ServerConnectBeginEvent(address));
        }
    }
}
