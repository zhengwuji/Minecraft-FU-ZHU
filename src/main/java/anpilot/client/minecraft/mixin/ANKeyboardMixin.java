package anpilot.client.minecraft.mixin;

import anpilot.client.bootstrap.ANServiceRegistry;
import anpilot.client.features.event.impl.KeyBoardEvent;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class ANKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo callback) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;

        KeyBoardEvent event = new KeyBoardEvent(key, scancode, action, modifiers);
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(event);
        if (event.isCancelled()) {
            callback.cancel();
            return;
        }

        if (action == GLFW.GLFW_PRESS) {
            ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().onKeyPressed(key);
        } else if (action == GLFW.GLFW_RELEASE) {
            ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().onKeyReleased(key);
        }
    }
}
