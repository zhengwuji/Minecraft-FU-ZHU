package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.Items

class ANElytraReplace : ANBaseModule(
    name = "ElytraReplace",
    description = "当身上鞘翅耐久过低时自动换上满耐久的新鞘翅",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动换鞘翅"
) {
    val durability = addSetting(ANSetting("Durability", 5f, 0f, 100f))

    override fun onTick() {
        val player = Minecraft.getInstance().player ?: return
        val chest = player.getItemBySlot(EquipmentSlot.CHEST)
        if (chest.`is`(Items.ELYTRA)) {
            val currentDur = 100f - (chest.damageValue.toFloat() / chest.maxDamage.toFloat()) * 100f
            if (currentDur <= durability.value) {
                val result = findReplacementElytra()
                if (result.found()) {
                    val fromMenuSlot = result.slot()
                    clickSlot(fromMenuSlot)
                    clickSlot(6) 
                    clickSlot(fromMenuSlot)
                    
                    val connection = Minecraft.getInstance().connection
                    if (connection != null) {
                        connection.send(ServerboundContainerClosePacket(player.containerMenu.containerId))
                    }
                    
                    AgentUtils.sendMessage("Swapping the old elytra for a new one!")
                }
            }
        }
    }

    private fun findReplacementElytra(): SearchInvResult {
        val player = Minecraft.getInstance().player ?: return SearchInvResult(-1, false)
        for (slot in 0..35) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && stack.`is`(Items.ELYTRA)) {
                val durPct = 100f - (stack.damageValue.toFloat() / stack.maxDamage.toFloat()) * 100f
                if (durPct > durability.value) {
                    val menuSlot = if (slot < 9) slot + 36 else slot
                    return SearchInvResult(menuSlot, true)
                }
            }
        }
        return SearchInvResult(-1, false)
    }

    private fun clickSlot(menuSlotId: Int) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val gameMode = minecraft.gameMode ?: return
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlotId, 0, ClickType.PICKUP, player)
    }

    private class SearchInvResult(private val slot: Int, private val found: Boolean) {
        fun slot(): Int = slot
        fun found(): Boolean = found
    }
}
