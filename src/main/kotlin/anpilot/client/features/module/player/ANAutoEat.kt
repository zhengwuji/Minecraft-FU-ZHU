package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class ANAutoEat : ANBaseModule(
    name = "AutoEat",
    description = "当玩家饥饿度降低或血量偏低时自动切出背包食物进行食用补充",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动进食"
) {
    val hunger = addSetting(ANSetting("Hunger", 8f, 0f, 20f))
    val gapple = addSetting(ANSetting("Gapple", false))
    val swapBack = addSetting(ANSetting("SwapBack", true))

    private var eating = false
    private var prevSlot = -1

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val food = player.foodData.foodLevel

        if (food > hunger.value) {
            if (eating) stopEating()
            return
        }

        val hasFoodInHand = isGoodFood(player.mainHandItem) || isGoodFood(player.offhandItem)
        if (!hasFoodInHand && !switchToFood()) {
            if (eating) stopEating()
            return
        }

        startEating()
    }

    override fun onDisable() {
        if (eating) stopEating()
    }

    private fun startEating() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        eating = true
        if (minecraft.screen != null && !player.isUsingItem) return
        minecraft.options.keyUse.isDown = true
    }

    private fun stopEating() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        eating = false
        minecraft.options.keyUse.isDown = false

        if (swapBack.value && prevSlot != -1) {
            player.inventory.selected = prevSlot
            minecraft.connection?.send(ServerboundSetCarriedItemPacket(prevSlot))
            prevSlot = -1
        }
    }

    private fun switchToFood(): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        for (slot in 0 until 9) {
            val stack = player.inventory.getItem(slot)
            if (!isGoodFood(stack)) continue
            if (prevSlot == -1) prevSlot = player.inventory.selected
            player.inventory.selected = slot
            minecraft.connection?.send(ServerboundSetCarriedItemPacket(slot))
            return true
        }
        return false
    }

    private fun isGoodFood(stack: ItemStack): Boolean {
        if (!stack.isEdible) return false
        val item: Item = stack.item
        if (!gapple.value && (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE)) return false
        return true
    }
}
