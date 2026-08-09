package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.MoveEvent
import anpilot.client.features.module.ANBaseModule
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.world.phys.AABB
import kotlin.math.abs

class ANSafeWalk : ANBaseModule(
    name = "SafeWalk",
    description = "阻止玩家走出方块边缘，按左Shift暂时放行",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "安全行走"
) {
    @ANEventHandler
    fun onMove(event: MoveEvent) {
        val player = mc.player ?: return
        if (!player.onGround()) return
        if (isLeftShiftDown()) return
        if (abs(event.x) < MIN_MOVE && abs(event.z) < MIN_MOVE) return
        if (hasGroundBelow(player.boundingBox.move(event.x, 0.0, event.z))) return

        event.x = 0.0
        event.z = 0.0
        event.modify = true
    }

    private fun hasGroundBelow(box: AABB): Boolean {
        val player = mc.player ?: return true
        val level = mc.level ?: return true
        val probe = box.move(0.0, -SUPPORT_DEPTH, 0.0)
        return !level.noCollision(player, probe)
    }

    private fun isLeftShiftDown(): Boolean {
        return InputConstants.isKeyDown(mc.window.window, InputConstants.KEY_LSHIFT)
    }

    private companion object {
        const val MIN_MOVE = 1.0E-5
        const val SUPPORT_DEPTH = 0.5
    }
}
