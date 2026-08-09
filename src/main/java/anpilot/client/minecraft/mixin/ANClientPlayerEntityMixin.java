package anpilot.client.minecraft.mixin;

import anpilot.client.bootstrap.ANServiceRegistry;
import anpilot.client.features.event.impl.EventPostSync;
import anpilot.client.features.event.impl.EventPreSync;
import anpilot.client.features.event.impl.MoveEvent;
import anpilot.client.features.event.impl.PlayerUpdateEvent;
import anpilot.client.features.event.impl.TickMovementEvent;
import anpilot.client.features.module.movement.ANAntiKnockBack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class ANClientPlayerEntityMixin extends AbstractClientPlayer {
    @Unique
    private Runnable postAction;

    public ANClientPlayerEntityMixin(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void sendMovementPacketsHook(CallbackInfo info) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new PlayerUpdateEvent.PrePacket());
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new anpilot.client.features.event.impl.MovementPacketsEvent.Update());

        EventPreSync event = new EventPreSync(getYRot(), getXRot());
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(event);
        postAction = event.getPostAction();
        if (event.isCancelled()) info.cancel();
    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void onMoveTowardsClosestSpace(double x, double z, CallbackInfo info) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        Object module = ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().get("AntiKnockBack");
        if (module instanceof ANAntiKnockBack antiKnockBack && antiKnockBack.shouldCancelBlockPush()) {
            info.cancel();
        }
    }

    @Inject(method = "sendPosition", at = @At("RETURN"), cancellable = true)
    private void sendMovementPacketsPostHook(CallbackInfo info) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        EventPostSync event = new EventPostSync();
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(event);
        if (postAction != null) {
            postAction.run();
            postAction = null;
        }
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new PlayerUpdateEvent.Post());
        if (event.isCancelled()) info.cancel();
    }

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void sendMovementPacketHook(ClientPacketListener instance, Packet<?> packet) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) {
            instance.send(packet);
            return;
        }

        anpilot.client.features.event.impl.MovementPacketsEvent.Send event =
            new anpilot.client.features.event.impl.MovementPacketsEvent.Send(packet);
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(event);
        instance.send(event.isCancelled() ? event.getPacket() : packet);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tickHook(CallbackInfo info) {
        if (ANServiceRegistry.INSTANCE.isInitialized()) {
            ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new PlayerUpdateEvent.Pre());
            ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new PlayerUpdateEvent());
        }
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void tickMovement(CallbackInfo ci) {
        if (ANServiceRegistry.INSTANCE.isInitialized()) {
            ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(new TickMovementEvent());
        }
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void onMoveHook(MoverType movementType, Vec3 movement, CallbackInfo ci) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        MoveEvent event = MoveEvent.Companion.get(movement.x, movement.y, movement.z);
        ANServiceRegistry.INSTANCE.getRuntime().getEventBus().post(event);
        if (event.isCancelled()) {
            ci.cancel();
        } else if (event.getModify()) {
            ci.cancel();
            super.move(movementType, new Vec3(event.getX(), event.getY(), event.getZ()));
        }
    }



    @Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
    private void isMovingSlowlyHook(CallbackInfoReturnable<Boolean> cir) {
        if (!ANServiceRegistry.INSTANCE.isInitialized()) return;
        Object module = ANServiceRegistry.INSTANCE.getRuntime().getModuleManager().get("NoSlow");
        if (module instanceof anpilot.client.features.module.movement.ANNoSlow && ((anpilot.client.features.module.movement.ANNoSlow) module).getEnabled()) {
            anpilot.client.features.module.movement.ANNoSlow noSlow = (anpilot.client.features.module.movement.ANNoSlow) module;
            if (isVisuallyCrawling()) {
                if (noSlow.getCrawling().getValue()) {
                    cir.setReturnValue(false);
                }
            } else {
                if (noSlow.getSneaking().getValue()) {
                    cir.setReturnValue(false);
                }
            }
        }
    }

    @Override
    protected float getFlyingSpeed() {
        return super.getFlyingSpeed();
    }

    @Override
    public boolean isInWater() {
        return super.isInWater();
    }
}
