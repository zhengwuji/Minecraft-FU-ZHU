package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.ai.utils.FireworkUtils
import anpilot.client.features.module.ANBaseModule
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.Item

class ANAutoElytra : ANBaseModule(
    name = "AutoElytra",
    description = "自动换上鞘翅并起飞",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动鞘翅"
) {
    private var hasUsedFirework = false
    private var jumpDelay = 0

    override fun onEnable() {
        hasUsedFirework = false
        jumpDelay = 0
    }

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val connection = minecraft.connection ?: return

        val chest: ItemStack = player.getItemBySlot(EquipmentSlot.CHEST)
        if (chest.item != Items.ELYTRA) {
            val elytra = findItemInInventory(Items.ELYTRA)
            if (elytra.found()) {
                move(elytra.slot()).toArmor(2)
                return
            } else {
                disable()
                return
            }
        }

        if (player.isFallFlying) {
            if (!hasUsedFirework) {
                FireworkUtils.useFirework()
                hasUsedFirework = true
            }
            disable()
            return
        }

        if (!player.onGround()) {
            if (jumpDelay <= 0) {
                connection.send(ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                jumpDelay = 2
            } else {
                jumpDelay--
            }
        } else {
            player.jumpFromGround()
        }
    }

    private fun findItemInInventory(item: Item): SearchInvResult {
        val player = Minecraft.getInstance().player ?: return SearchInvResult(-1, false)
        for (slot in 36 downTo 0) {
            if (player.inventory.getItem(slot).item == item) return SearchInvResult(if (slot < 9) slot + 36 else slot, true)
        }
        return SearchInvResult(-1, false)
    }

    private fun move(from: Int): MoveAction = MoveAction(from)

    private class SearchInvResult(private val slot: Int, private val found: Boolean) {
        fun slot(): Int = slot
        fun found(): Boolean = found
    }

    private class MoveAction(private val from: Int) {
        fun toArmor(armorSlotId: Int) {
            val minecraft = Minecraft.getInstance()
            val player = minecraft.player ?: return
            val gameMode = minecraft.gameMode ?: return
            val fromId = toMenuSlot(from)
            val toId = InventoryMenu.ARMOR_SLOT_START + (3 - armorSlotId)
            gameMode.handleInventoryMouseClick(player.containerMenu.containerId, fromId, 0, ClickType.PICKUP, player)
            gameMode.handleInventoryMouseClick(player.containerMenu.containerId, toId, 0, ClickType.PICKUP, player)
            gameMode.handleInventoryMouseClick(player.containerMenu.containerId, fromId, 0, ClickType.PICKUP, player)
        }

        private fun toMenuSlot(slot: Int): Int = if (slot < 9) InventoryMenu.USE_ROW_SLOT_START + slot else slot
    }
}
