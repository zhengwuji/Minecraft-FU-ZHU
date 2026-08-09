package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.minecraft.mixin.accessor.ANBossHealthOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

class ANPlanMove : ANBaseModule(
    name = "PlanMove",
    description = "根据设定的前、后、左、右距离与延时自动控制玩家走位移动，配合袭击塔模式自动喝不详之瓶",
    category = ANModuleCategory.MISC,
    chineseName = "计划移动"
) {
    val mode = addSetting(ANSetting("PathFinder", PathMode.Distance))

    val forward = addSetting(ANSetting("Forward", 0, 0, 20))
    val forwardDelay = addSetting(ANSetting("ForwardDelay", 0, 0, 20) { forward.value > 0 })

    val backward = addSetting(ANSetting("Backward", 0, 0, 20))
    val backwardDelay = addSetting(ANSetting("BackwardDelay", 0, 0, 20) { backward.value > 0 })

    val left = addSetting(ANSetting("Left", 0, 0, 20))
    val leftDelay = addSetting(ANSetting("LeftDelay", 0, 0, 20) { left.value > 0 })

    val right = addSetting(ANSetting("Right", 0, 0, 20))
    val rightDelay = addSetting(ANSetting("RightDelay", 0, 0, 20) { right.value > 0 })

    val badOmen = addSetting(ANSetting("BadOmen", false))
    val swapBack = addSetting(ANSetting("SwapBack", true) { badOmen.value })

    private var prevSlot = -1
    private var drinking = false
    private var moving = false
    private var startPos: Vec3? = null
    private var timeCounter = 0
    private var delayCounter = 0
    private var stage = 0
    private var inDelay = false
    private var moveOnce = false

    override fun onEnable() {
        resetMovement()
        moveOnce = false
        prevSlot = -1
        drinking = false
        stopMoving()
    }

    override fun onDisable() {
        stopDrinking()
        resetMovement()
        stopMoving()
    }

    override fun onTick() {
        val player = mc.player ?: return

        if (!badOmen.value) {
            planMoveTick(loop = true)
            return
        }

        val hasBadOmen = player.hasEffect(MobEffects.BAD_OMEN)
        if (!hasBadOmen && hasOmenBottle() && !hasRaidBossBar()) {
            val found = isHandOmen(InteractionHand.MAIN_HAND) || isHandOmen(InteractionHand.OFF_HAND) || switchToOmen()
            if (!found) {
                if (drinking) stopDrinking()
                disableWithMessage("你没有不详之瓶！")
                return
            }
            startDrinking()
            return
        }

        if (drinking) {
            stopDrinking()
            resetMovement()
            moveOnce = true
        }

        if (player.hasEffect(MobEffects.BAD_OMEN) && moveOnce) {
            planMoveTick(loop = false)
        }
    }

    private fun startDrinking() {
        val player = mc.player ?: return
        drinking = true
        if (mc.screen != null && !player.isUsingItem) return
        mc.options.keyUse.isDown = true
    }

    private fun stopDrinking() {
        val player = mc.player ?: return
        drinking = false
        mc.options.keyUse.isDown = false
        if (swapBack.value && prevSlot != -1) {
            player.inventory.selected = prevSlot
            mc.connection?.send(ServerboundSetCarriedItemPacket(prevSlot))
            prevSlot = -1
        }
    }

    private fun planMoveTick(loop: Boolean) {
        if (inDelay) {
            delayCounter++
            if (delayCounter >= delayForStage(stage)) {
                delayCounter = 0
                inDelay = false
                if (loop) {
                    stage = (stage + 1) % 4
                } else if (stage >= 3) {
                    moveOnce = false
                    stage = 0
                } else {
                    stage++
                }
            }
            return
        }

        val done = when (stage) {
            0 -> move(forward.value) { mc.options.keyUp.isDown = true }
            1 -> move(backward.value) { mc.options.keyDown.isDown = true }
            2 -> move(left.value) { mc.options.keyLeft.isDown = true }
            3 -> move(right.value) { mc.options.keyRight.isDown = true }
            else -> true
        }

        if (done) {
            inDelay = true
            stopMoving()
        }
    }

    private fun move(value: Int, action: () -> Unit): Boolean {
        return if (mode.value == PathMode.Distance) moveByDistance(value.toDouble(), action) else moveByTime(value, action)
    }

    private fun moveByDistance(distance: Double, action: () -> Unit): Boolean {
        val player = mc.player ?: return true
        if (distance <= 0.0) return true
        if (!moving) {
            startPos = player.position()
            action()
            moving = true
            return false
        }

        val start = startPos ?: player.position()
        if (player.position().distanceTo(start) < distance) return false
        moving = false
        stopMoving()
        return true
    }

    private fun moveByTime(seconds: Int, action: () -> Unit): Boolean {
        val ticks = seconds * 20
        if (ticks <= 0) return true
        if (!moving) {
            timeCounter = 0
            action()
            moving = true
            return false
        }

        timeCounter++
        if (timeCounter < ticks) return false
        moving = false
        stopMoving()
        return true
    }

    private fun delayForStage(stage: Int): Int {
        return when (stage) {
            0 -> forwardDelay.value * 20
            1 -> backwardDelay.value * 20
            2 -> leftDelay.value * 20
            3 -> rightDelay.value * 20
            else -> 0
        }
    }

    private fun hasOmenBottle(): Boolean {
        return false
    }

    private fun isHandOmen(hand: InteractionHand): Boolean {
        return false
    }

    private fun switchToOmen(): Boolean {
        return false
    }

    private fun hasRaidBossBar(): Boolean {
        val overlay = runCatching { mc.gui.bossOverlay as ANBossHealthOverlayAccessor }.getOrNull() ?: return false
        return overlay.events.values.any { event ->
            val name = event.name.string
            name.contains("袭击") || name.contains("Raid", ignoreCase = true)
        }
    }

    private fun stopMoving() {
        mc.options.keyUp.isDown = false
        mc.options.keyDown.isDown = false
        mc.options.keyLeft.isDown = false
        mc.options.keyRight.isDown = false
    }

    private fun resetMovement() {
        moving = false
        inDelay = false
        delayCounter = 0
        timeCounter = 0
        stage = 0
        startPos = null
    }

    private fun disableWithMessage(message: String) {
        mc.player?.sendSystemMessage(Component.literal("[ANPilot] $message"))
        disable()
    }

    enum class PathMode {
        Distance,
        Time
    }
}
