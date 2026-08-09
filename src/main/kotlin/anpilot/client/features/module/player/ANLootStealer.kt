package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANTickEvent
import anpilot.client.features.module.ANBaseModule
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ItemSelectSetting

class ANLootStealer : ANBaseModule(
    name = "LootStealer",
    description = "快速从箱子拿取选中的物品",
    category = ANModuleCategory.PLAYER,
    chineseName = "箱子秒偷",
    defaultState = ANModuleState.DISABLED
) {
    val selectedBlocks = addSetting(ANSetting("Blocks", ItemSelectSetting(ArrayList())))
    private var isStealing = false

    @ANEventHandler
    fun onTick(event: ANTickEvent) {
        if (mc.player == null) return
        val screen = mc.screen
        
        if (screen is ContainerScreen) {
            if (isStealing) return
            
            val container = screen.menu
            val containerSlots = container.slots.filter { it.container == container.container }
            
            var stoleSomething = false
            for (slot in containerSlots) {
                if (isHighValue(slot.item)) {
                    mc.gameMode?.handleInventoryMouseClick(
                        container.containerId,
                        slot.index,
                        0,
                        ClickType.QUICK_MOVE,
                        mc.player!!
                    )
                    stoleSomething = true
                }
            }
            
            if (stoleSomething) {
                mc.player?.closeContainer()
            }
            isStealing = true
        } else {
            isStealing = false
        }
    }

    private fun isHighValue(itemStack: ItemStack): Boolean {
        if (itemStack.isEmpty) return false
        return selectedBlocks.value.contains(itemStack.item)
    }
}
