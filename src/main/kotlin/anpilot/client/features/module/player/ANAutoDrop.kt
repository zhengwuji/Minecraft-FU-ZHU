package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ItemSelectSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.inventory.ClickType

class ANAutoDrop : ANBaseModule(
    name = "AutoDrop",
    description = "自动扫描背包，迅速丢弃在垃圾黑名单中选中的无用杂物物品",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动丢弃"
) {
    val items = addSetting(ANSetting("Items", ItemSelectSetting(listOf(
        "allium", "azure_bluet", "blue_orchid", "cornflower", "dandelion",
        "lilac", "lily_of_the_valley", "orange_tulip", "oxeye_daisy", "peony",
        "pink_tulip", "poisonous_potato", "poppy", "red_tulip", "rose_bush",
        "rotten_flesh", "sunflower", "wheat_seeds", "white_tulip"
    ))))

    override fun onTick() {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return

        if (mc.screen is AbstractContainerScreen<*> && mc.screen !is InventoryScreen) {
            return
        }

        for (slot in 9 until 45) {
            var adjustedSlot = slot
            if (adjustedSlot >= 36) {
                adjustedSlot -= 36
            }
            val stack = player.inventory.getItem(adjustedSlot)
            if (stack.isEmpty) continue

            if (items.value.contains(stack.item)) {
                gameMode.handleInventoryMouseClick(
                    player.containerMenu.containerId,
                    slot,
                    1,
                    ClickType.THROW,
                    player
                )
            }
        }
    }
}
