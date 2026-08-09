package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack

class ANHotbarFill : ANBaseModule(
    name = "HotbarFill",
    description = "当快捷栏中物品消耗低于设定堆叠数时自动从背包中寻找同类物品补齐",
    category = ANModuleCategory.PLAYER,
    chineseName = "快捷栏填充"
) {
    val threshold = addSetting(ANSetting("Threshold", 10f, 1f, 64f))
    val delay = addSetting(ANSetting("Delay", 100f, 0f, 1000f))

    private var lastMs = 0L

    override fun onTick() {
        val now = System.currentTimeMillis()
        if (now - lastMs < delay.value) return

        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val gameMode = minecraft.gameMode ?: return
        val inventory = player.inventory

        for (hotbarSlot in 0 until 9) {
            val target = inventory.getItem(hotbarSlot)
            if (!needsRefill(target)) continue
            val invSlot = findMatchingInventorySlot(target)
            if (invSlot == -1) continue

            val containerSlot = if (player.containerMenu != player.inventoryMenu) {
                val menuSlot = player.containerMenu.slots.firstOrNull { slot ->
                    slot.container == player.inventory && slot.containerSlot == invSlot
                }
                menuSlot?.index ?: -1
            } else {
                invSlot
            }

            if (containerSlot == -1) continue

            gameMode.handleInventoryMouseClick(
                player.containerMenu.containerId,
                containerSlot,
                0,
                ClickType.QUICK_MOVE,
                player
            )
            lastMs = now
            break
        }
    }

    private fun needsRefill(stack: ItemStack): Boolean {
        return !stack.isEmpty && stack.isStackable && stack.count <= threshold.value.toInt() && stack.count < stack.maxStackSize
    }

    private fun findMatchingInventorySlot(target: ItemStack): Int {
        val inventory = Minecraft.getInstance().player?.inventory ?: return -1
        for (slot in 9 until 36) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            if (ItemStack.isSameItemSameTags(stack, target)) return slot
        }
        return -1
    }
}
