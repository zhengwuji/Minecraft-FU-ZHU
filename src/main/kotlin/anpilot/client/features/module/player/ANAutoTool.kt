package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANAttackBlockEvent
import anpilot.client.features.module.ANBaseModule
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

class ANAutoTool : ANBaseModule(
    name = "AutoTool",
    description = "挖掘不同材质方块时自动无缝切出快捷栏中开采速度最快的工具",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动工具",
    defaultState = ANModuleState.ENABLED
) {
    private var oldSlot = -1
    private var lastDigTime = 0L
    private val switchBackDelay = 800L


    override fun onDisable() {
        val player = Minecraft.getInstance().player ?: return
        if (oldSlot != -1) {
            player.inventory.selected = oldSlot
            oldSlot = -1
        }
    }

    @ANEventHandler
    fun onAttackBlock(event: ANAttackBlockEvent) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val pos = event.blockPos as? BlockPos ?: return
        val state = level.getBlockState(pos)
        if (state.isAir || state.getDestroySpeed(level, pos) < 0f) return

        val bestSlot = findBestTool(state)
        if (bestSlot != -1 && bestSlot != player.inventory.selected) {
            if (oldSlot == -1) oldSlot = player.inventory.selected
            player.inventory.selected = bestSlot
            lastDigTime = System.currentTimeMillis()
        }
    }

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val gameMode = minecraft.gameMode ?: return
        if (gameMode.isDestroying) {
            lastDigTime = System.currentTimeMillis()
            return
        }

        if (oldSlot != -1 && System.currentTimeMillis() - lastDigTime > switchBackDelay) {
            player.inventory.selected = oldSlot
            oldSlot = -1
        }
    }

    private fun findBestTool(state: BlockState): Int {
        val player = Minecraft.getInstance().player ?: return -1
        var bestSlot = -1
        var maxSpeed = 1.0f
        for (slot in 0 until 9) {
            val stack: ItemStack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val speed = stack.getDestroySpeed(state)
            if (speed > maxSpeed) {
                maxSpeed = speed
                bestSlot = slot
            }
        }
        return bestSlot
    }
}
