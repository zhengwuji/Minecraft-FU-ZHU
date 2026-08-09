package anpilot.client.minecraft.mixin;

import anpilot.client.bootstrap.ANServiceRegistry;
import anpilot.client.features.module.movement.ANAntiWeb;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WebBlock.class)
public abstract class ANCobwebBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void onEntityCollision(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Object module = ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().get("AntiWeb");
        if (entity == minecraft.player && module instanceof ANAntiWeb antiWeb && antiWeb.getEnabled()) {
            ci.cancel();
            Vec3 multiplier = antiWeb.webMultiplier();
            if (multiplier.lengthSqr() > 1.0E-7D) {
                entity.makeStuckInBlock(state, multiplier);
            }
        }
    }
}
