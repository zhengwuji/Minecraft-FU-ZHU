package anpilot.client.features.manager

import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.ClientRotationEvent
import anpilot.client.features.event.impl.InteractItemEvent
import anpilot.client.features.event.impl.MovementPacketsEvent
import anpilot.client.features.event.impl.PlayerInputEvent
import anpilot.client.features.event.impl.PlayerJumpEvent
import anpilot.client.features.event.impl.PlayerTransformsEvent
import anpilot.client.features.event.impl.PlayerUpdateEvent
import anpilot.client.features.event.impl.PlayerVelocityEvent
import anpilot.client.features.event.impl.RotationUpdateEvent
import anpilot.client.features.event.impl.TravelEvent
import anpilot.client.features.manager.rotation.MovementCorrection
import anpilot.client.features.manager.rotation.MovementFix
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationApplier
import anpilot.client.features.manager.rotation.RotationController
import anpilot.client.features.manager.rotation.RotationPriority
import anpilot.client.features.manager.rotation.RotationRequest
import anpilot.client.features.module.anpilot.ANPilotClient
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.util.Mth
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import kotlin.math.abs
import kotlin.math.pow

class ANRotationManager {
    private val mc = Minecraft.getInstance()
    val controller = RotationController()
    val serverRotation: Rotation
        get() = controller.state.actualServerRotation

    var clientRotation: Rotation? = null
        private set

    private var initialized = false
    private var activeRequest: RotationRequest? = null
    private var travelRestoreRotation: Rotation? = null
    private var preJumpRotation: Rotation? = null

    fun requestRotation(
        rotation: Rotation,
        priority: RotationPriority,
        owner: String,
        yawStep: Float = 360.0f,
        pitchStep: Float = 180.0f,
        movementFix: MovementFix? = null,
        mouseSensitivityFix: Boolean = true
    ) {
        val resolvedMovementFix = movementFix ?: ANPilotClient.globalMovementFix()
        controller.requestRotation(
            RotationRequest(
                rotation = rotation,
                priority = priority,
                owner = owner,
                yawStep = yawStep,
                pitchStep = pitchStep,
                movementFix = resolvedMovementFix,
                mouseSensitivityFix = mouseSensitivityFix
            )
        )
    }

    fun sendInstantRotation(rotation: Rotation, mouseSensitivityFix: Boolean = true) {
        val player = mc.player ?: return
        val connection = mc.connection ?: return
        val fixedRotation = if (mouseSensitivityFix) {
            applyMouseSensitivityFix(rotation.copy().wrap(), controller.state.actualServerRotation)
        } else {
            rotation.copy().wrap()
        }
        connection.send(
            ServerboundMovePlayerPacket.PosRot(
                player.position().x,
                player.position().y,
                player.position().z,
                fixedRotation.yaw,
                fixedRotation.pitch,
                player.onGround()
            )
        )
        controller.state.serverRotation = fixedRotation.copy()
        controller.state.appliedRotation = fixedRotation.copy()
        controller.state.updateActualServerRotation(fixedRotation)
    }

    fun isRotationReached(target: Rotation): Boolean {
        val current = controller.state.actualServerRotation
        val diffYaw = abs(Mth.wrapDegrees(target.yaw - current.yaw))
        val diffPitch = abs(target.pitch - current.pitch)
        return diffYaw < 1.0f && diffPitch < 1.0f
    }

    fun setSilentRotation(rotation: Rotation) {
        sendInstantRotation(rotation)
    }

    fun resetSilentRotation() {
        val player = mc.player ?: return
        sendInstantRotation(clientRotation ?: Rotation(player))
    }

    fun clearClientRotation() {
        clientRotation = null
    }

    fun hasClientRotation(): Boolean = clientRotation != null

    @ANEventHandler
    fun onRotationUpdate(event: RotationUpdateEvent) {
        controller.state.updateActualServerRotation(Rotation(event.yaw, event.pitch).wrap())
    }

    @ANEventHandler(priority = ANEventPriority.LOWEST)
    fun onUpdatePre(event: PlayerUpdateEvent.Pre) {
        val player = mc.player ?: return
        val naturalRotation = Rotation(player).wrap()

        if (!initialized) {
            controller.state.actualServerRotation = naturalRotation.copy()
            controller.state.serverRotation = naturalRotation.copy()
            initialized = true
        }

        controller.state.visualRotation = naturalRotation.copy()
        clientRotation = null

        val legacyEvent = ClientRotationEvent(naturalRotation.copy())
        ANServiceRegistry.runtime.eventBus.post(legacyEvent)
        if (legacyEvent.isCancelled()) {
            clientRotation = legacyEvent.rotation.copy().wrap()
            requestRotation(
                rotation = legacyEvent.rotation.copy().wrap(),
                priority = RotationPriority.COMBAT,
                owner = "ClientRotationEvent",
                yawStep = 360.0f,
                pitchStep = 180.0f,
                mouseSensitivityFix = false
            )
        }

        activeRequest = controller.getHighestPriorityRequest()
        controller.state.rotationActive = activeRequest != null

        val request = activeRequest
        controller.state.serverRotation = if (request != null) {
            val steppedRotation = stepRotation(
                controller.state.actualServerRotation,
                request.rotation.copy().wrap(),
                request.yawStep,
                request.pitchStep
            )
            if (request.mouseSensitivityFix) {
                applyMouseSensitivityFix(steppedRotation, controller.state.actualServerRotation)
            } else {
                steppedRotation
            }
        } else {
            naturalRotation.copy()
        }

        controller.clearRequests()
    }

    @ANEventHandler
    fun onUpdatePrePacket(event: PlayerUpdateEvent.PrePacket) {
        val player = mc.player ?: return
        if (controller.state.rotationActive) {
            controller.state.appliedRotation = controller.state.serverRotation.copy()
            RotationApplier.apply(player, controller.state.appliedRotation)
        }
    }

    @ANEventHandler
    fun onUpdatePost(event: PlayerUpdateEvent.Post) {
        val player = mc.player ?: return
        if (controller.state.rotationActive) {
            RotationApplier.restore(player, controller.state.visualRotation)
        }
    }

    @ANEventHandler
    fun onJumpPre(event: PlayerJumpEvent.Pre) {
        val player = mc.player ?: return
        preJumpRotation = Rotation(player)
        if (controller.state.rotationActive) {
            RotationApplier.apply(player, controller.state.serverRotation)
        }
    }

    @ANEventHandler
    fun onJumpPost(event: PlayerJumpEvent.Post) {
        val player = mc.player ?: return
        preJumpRotation?.let { RotationApplier.restore(player, it) }
        preJumpRotation = null
    }

    @ANEventHandler
    fun onPlayerVelocity(event: PlayerVelocityEvent) {
        val request = activeRequest ?: return
        if (request.movementFix == MovementFix.OFF) return
        event.cancel()
        event.yaw = controller.state.serverRotation.yaw
    }

    @ANEventHandler
    fun onPlayerInput(event: PlayerInputEvent) {
        if (activeRequest?.movementFix != MovementFix.FREE) return
        val corrected = MovementCorrection.correct(
            event.movementForward,
            event.movementSideways,
            controller.state.visualRotation.yaw,
            controller.state.serverRotation.yaw
        )
        event.cancel()
        event.movementForward = corrected.y
        event.movementSideways = corrected.x
    }

    @ANEventHandler
    fun onTravelPre(event: TravelEvent.Pre) {
        val player = mc.player ?: return
        val request = activeRequest ?: return
        when (request.movementFix) {
            MovementFix.OFF -> return
            MovementFix.FOCUSED,
            MovementFix.FREE -> {
                val visualRotation = Rotation(player)
                travelRestoreRotation = visualRotation
                player.yRot = controller.state.serverRotation.yaw
                event.movementInput = MovementCorrection.correct(
                    event.movementInput,
                    visualRotation.yaw,
                    controller.state.serverRotation.yaw
                )
            }
        }
    }

    @ANEventHandler
    fun onTravelPost(event: TravelEvent.Post) {
        val player = mc.player ?: return
        travelRestoreRotation?.let { RotationApplier.restore(player, it) }
        travelRestoreRotation = null
    }

    @ANEventHandler
    fun onMovementPacketsSend(event: MovementPacketsEvent.Send) {
        val packet = event.packet

        if (packet is ServerboundMovePlayerPacket) {
            val serverYaw = controller.state.serverRotation.yaw
            val serverPitch = controller.state.serverRotation.pitch
            val player = mc.player ?: return

            val diffYaw = abs(Mth.wrapDegrees(player.yRot - serverYaw))
            val diffPitch = abs(player.xRot - serverPitch)

            if (controller.state.rotationActive && (diffYaw > 0.001f || diffPitch > 0.001f || !packet.hasRotation())) {
                event.cancel()
                event.packet = when (packet) {
                    is ServerboundMovePlayerPacket.PosRot -> ServerboundMovePlayerPacket.PosRot(
                        packet.getX(0.0),
                        packet.getY(0.0),
                        packet.getZ(0.0),
                        serverYaw,
                        serverPitch,
                        packet.isOnGround
                    )
                    is ServerboundMovePlayerPacket.Rot -> ServerboundMovePlayerPacket.Rot(
                        serverYaw,
                        serverPitch,
                        packet.isOnGround
                    )
                    else -> ServerboundMovePlayerPacket.PosRot(
                        packet.getX(player.x),
                        packet.getY(player.y),
                        packet.getZ(player.z),
                        serverYaw,
                        serverPitch,
                        packet.isOnGround
                    )
                }
            }

            val finalPacket = event.packet as ServerboundMovePlayerPacket
            val currentActual = controller.state.actualServerRotation
            val newYaw = finalPacket.getYRot(currentActual.yaw)
            val newPitch = finalPacket.getXRot(currentActual.pitch)
            controller.state.updateActualServerRotation(Rotation(newYaw, newPitch).wrap())
        }
    }

    @ANEventHandler
    fun onInteractItemPre(event: InteractItemEvent.Pre) {
        val player = mc.player ?: return
        if (controller.state.rotationActive || PROJECTILE_ITEMS.contains(event.item)) {
            RotationApplier.apply(player, controller.state.serverRotation)
        }
    }

    @ANEventHandler
    fun onInteractItemPost(event: InteractItemEvent.Post) {
        val player = mc.player ?: return
        if (controller.state.rotationActive || PROJECTILE_ITEMS.contains(event.item)) {
            RotationApplier.restore(player, controller.state.visualRotation)
        }
    }

    @ANEventHandler
    fun onPlayerTransforms(event: PlayerTransformsEvent) {
        val yaw = Mth.wrapDegrees(controller.state.actualServerRotation.yaw)
        val delta = event.tickDelta * 0.05f
    }

    fun isFacingYaw(yaw: Float): Boolean {
        val dyaw = Mth.wrapDegrees(controller.state.actualServerRotation.yaw - yaw)
        return abs(dyaw) <= 0.1f
    }

    fun isFacingPitch(pitch: Float): Boolean {
        val fixedPitch = Mth.clamp(pitch, -90.0f, 90.0f)
        return abs(controller.state.actualServerRotation.pitch - fixedPitch) <= 0.1f
    }

    fun isFacing(yaw: Float, pitch: Float): Boolean = isFacingYaw(yaw) && isFacingPitch(pitch)

    private fun stepRotation(current: Rotation, target: Rotation, yawStep: Float, pitchStep: Float): Rotation {
        val deltaYaw = Mth.wrapDegrees(target.yaw - current.yaw)
        val deltaPitch = target.pitch - current.pitch
        val steppedYaw = current.yaw + Mth.clamp(deltaYaw, -yawStep, yawStep)
        val steppedPitch = current.pitch + Mth.clamp(deltaPitch, -pitchStep, pitchStep)
        return Rotation(steppedYaw, steppedPitch).wrap()
    }

    private fun applyMouseSensitivityFix(rotation: Rotation, previous: Rotation): Rotation {
        val sensitivity = mc.options.sensitivity().get()
        val gcd = ((sensitivity * 0.6 + 0.2).pow(3.0) * 1.2).toFloat()
        if (gcd <= 0.0f) return rotation.wrap()

        val fixedYaw = rotation.yaw - (rotation.yaw - previous.yaw) % gcd
        val fixedPitch = rotation.pitch - (rotation.pitch - previous.pitch) % gcd
        return Rotation(fixedYaw, fixedPitch).wrap()
    }

    private companion object {
        private val PROJECTILE_ITEMS: List<Item> = listOf(
            Items.SNOWBALL,
            Items.EGG,
            Items.ENDER_PEARL,
            Items.EXPERIENCE_BOTTLE,
            Items.SPLASH_POTION,
            Items.LINGERING_POTION,
            Items.FIRE_CHARGE
        )
    }
}
