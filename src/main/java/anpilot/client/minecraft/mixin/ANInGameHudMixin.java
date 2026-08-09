package anpilot.client.minecraft.mixin;

import anpilot.client.bootstrap.ANServiceRegistry;
import anpilot.client.features.module.render.ANNoRender;
import anpilot.client.features.event.impl.Render2DEvent;
import anpilot.client.features.event.impl.ANMinecraftEvents;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class ANInGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void renderHook(GuiGraphics context, float partialTick, CallbackInfo ci) {
        if (ANServiceRegistry.INSTANCE.isInitialized()) {
            ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new Render2DEvent(context, partialTick));
            ANMinecraftEvents.INSTANCE.renderHud(context, partialTick);
        }
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void onRenderHotbar(float partialTick, GuiGraphics context, CallbackInfo ci) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        var hotBar = ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().get("HotBar");
        if (hotBar != null && hotBar.getEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void renderStatusEffectOverlayHook(GuiGraphics context, CallbackInfo ci) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        var potions = ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().get("Potions");
        if (potions != null && potions.getEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void onRenderVignette(GuiGraphics graphics, Entity camera, CallbackInfo ci) {
        ANNoRender noRender = noRender();
        if (noRender != null && noRender.getNoVignette().getValue()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void onRenderSpyglassOverlay(GuiGraphics graphics, float scopeScale, CallbackInfo ci) {
        ANNoRender noRender = noRender();
        if (noRender != null && noRender.getNoSpyglassOverlay().getValue()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void onRenderPortalOverlay(GuiGraphics graphics, float alpha, CallbackInfo ci) {
        ANNoRender noRender = noRender();
        if (noRender != null && noRender.getNoPortalOverlay().getValue()) {
            ci.cancel();
        }
    }



    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void onRenderSelectedItemName(GuiGraphics graphics, CallbackInfo ci) {
        ANNoRender noRender = noRender();
        if (noRender != null && noRender.getNoItemName().getValue()) {
            ci.cancel();
        }
    }

    @Unique
    private static ANNoRender noRender() {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return null;
        ANNoRender noRender = ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().noRender();
        if (noRender == null || !noRender.getEnabled()) return null;
        return noRender;
    }
}
