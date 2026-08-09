package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.player.Input
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3

class ANFreecam : ANBaseModule(
    name = "Freecam",
    description = "开启灵魂脱壳自由视角，自由穿墙移动与侦察周围环境",
    category = ANModuleCategory.RENDER,
    chineseName = "自由视角"
) {
    val speed = addSetting(ANSetting("Speed", 5.0f, 0.1f, 50.0f))

    var position: Vec3? = null
        private set

    private var lastPosition: Vec3? = null
    private var previousInput: Input? = null
    private var frozenInput: FreecamInput? = null
    private var velocity = Vec3.ZERO

    var yaw: Float = 0.0f
        private set

    var pitch: Float = 0.0f
        private set

    override fun onEnable() {
        val player = mc.player ?: return
        val partialTick = mc.frameTime
        val start = player.getEyePosition(partialTick)

        position = start
        lastPosition = start
        yaw = player.getViewYRot(partialTick)
        pitch = player.getViewXRot(partialTick)
        velocity = Vec3.ZERO

        previousInput = player.input
        frozenInput = FreecamInput().also { player.input = it }
    }

    override fun onDisable() {
        mc.player?.let { player ->
            val input = frozenInput
            if (input != null && player.input === input) {
                player.input = previousInput ?: Input()
            }
        }

        previousInput = null
        frozenInput = null
        position = null
        lastPosition = null
        velocity = Vec3.ZERO
    }

    override fun onTick() {
        val player = mc.player ?: run {
            disable()
            return
        }

        if (player.input !== frozenInput) {
            frozenInput = FreecamInput().also { player.input = it }
        }

        tickCamera()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        disable()
    }

    fun onMouseMove(cursorDeltaX: Double, cursorDeltaY: Double) {
        if (mc.screen != null) return
        yaw = Mth.wrapDegrees((yaw + cursorDeltaX.toFloat() * MOUSE_SENSITIVITY).toDouble()).toFloat()
        pitch = Mth.clamp(pitch + cursorDeltaY.toFloat() * MOUSE_SENSITIVITY, -90.0f, 90.0f)
    }

    fun getRenderPosition(tickDelta: Float): Vec3? {
        val current = position ?: return null
        val previous = lastPosition ?: current
        return previous.lerp(current, tickDelta.toDouble())
    }

    private fun tickCamera() {
        val current = position ?: return
        lastPosition = current

        val inputDirection = getMovement()
        val maxTickSpeed = speed.value.toDouble() / TICKS_PER_SECOND
        val targetVelocity = if (inputDirection.lengthSqr() > 0.0) {
            inputDirection.normalize().scale(maxTickSpeed)
        } else {
            Vec3.ZERO
        }

        val step = if (targetVelocity.lengthSqr() > velocity.lengthSqr()) {
            maxTickSpeed * ACCELERATION_STEP
        } else {
            maxTickSpeed * DECELERATION_STEP
        }

        velocity = approachVelocity(velocity, targetVelocity, step)
        if (velocity.lengthSqr() <= MIN_VELOCITY_SQ) {
            velocity = Vec3.ZERO
            return
        }

        position = current.add(velocity)
    }

    private fun approachVelocity(current: Vec3, target: Vec3, maxStep: Double): Vec3 {
        val delta = target.subtract(current)
        val distance = delta.length()
        if (distance <= maxStep || distance == 0.0) {
            return target
        }

        return current.add(delta.scale(maxStep / distance))
    }

    private fun getMovement(): Vec3 {
        if (mc.screen != null) return Vec3.ZERO
        val options = mc.options
        var movement = Vec3.ZERO

        val forward = Vec3.directionFromRotation(0.0f, yaw)
        val right = Vec3.directionFromRotation(0.0f, yaw + 90.0f)

        if (options.keyUp.isDown) movement = movement.add(forward)
        if (options.keyDown.isDown) movement = movement.subtract(forward)
        if (options.keyRight.isDown) movement = movement.add(right)
        if (options.keyLeft.isDown) movement = movement.subtract(right)
        if (options.keyJump.isDown) movement = movement.add(0.0, 1.0, 0.0)
        if (options.keyShift.isDown) movement = movement.add(0.0, -1.0, 0.0)

        return movement
    }

    private class FreecamInput : Input() {
        override fun tick(slowDown: Boolean, f: Float) {
            up = false
            down = false
            left = false
            right = false
            forwardImpulse = 0f
            leftImpulse = 0f
            jumping = false
            shiftKeyDown = false
        }
    }

    private companion object {
        private const val MOUSE_SENSITIVITY = 0.15f
        private const val TICKS_PER_SECOND = 20.0
        private const val ACCELERATION_STEP = 1.0 / 10.0
        private const val DECELERATION_STEP = 1.0 / 14.0
        private const val MIN_VELOCITY_SQ = 1.0E-8
    }
}
