package anpilot.client.minecraft.mixin;

import anpilot.client.bootstrap.ANServiceRegistry;
import anpilot.client.features.event.impl.EventEntitySpawnPost;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ANClientWorldMixin {
    @Inject(method = "addEntity", at = @At("RETURN"), cancellable = true)
    private void addEntityHookPost(int id, Entity entity, CallbackInfo ci) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        EventEntitySpawnPost event = new EventEntitySpawnPost(entity);
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(event);
        if (event.isCancelled()) ci.cancel();
    }

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void addDestroyBlockEffectHook(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (ANServiceRegistry.INSTANCE.isInitialized() &&
            ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().noRender() != null &&
            ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().noRender().getEnabled() &&
            ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().noRender().getNoBreakParticles().getValue()) {
            ci.cancel();
        }
    }
}
