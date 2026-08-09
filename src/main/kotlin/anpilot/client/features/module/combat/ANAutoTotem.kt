package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.Items

class ANAutoTotem : ANBaseModule(
    name = "AutoTotem",
    description = "将不死图腾替换到副手",
    category = ANModuleCategory.COMBAT,
    chineseName = "自动图腾",
    defaultState = ANModuleState.ENABLED
) {
    val delay = addSetting(ANSetting("Delay", 2f, 0f, 20f))

    private var clock = 0


    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val gameMode = minecraft.gameMode ?: return

        if (player.offhandItem.item == Items.TOTEM_OF_UNDYING) return
        if (!player.containerMenu.carried.isEmpty) return

        if (clock < delay.value.toInt()) {
            clock++
            return
        }

        val totemSlot = findTotem()
        if (totemSlot != -1 && player.containerMenu is InventoryMenu) {
            val containerId = player.containerMenu.containerId
            if (totemSlot < 9) {
                gameMode.handleInventoryMouseClick(containerId, InventoryMenu.SHIELD_SLOT, totemSlot, ClickType.SWAP, player)
            } else {
                gameMode.handleInventoryMouseClick(containerId, totemSlot, 0, ClickType.PICKUP, player)
                gameMode.handleInventoryMouseClick(containerId, InventoryMenu.SHIELD_SLOT, 0, ClickType.PICKUP, player)
                gameMode.handleInventoryMouseClick(containerId, totemSlot, 0, ClickType.PICKUP, player)
            }
            clock = 0
        }
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onPacket(event: PacketEvent.Receive) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val packet = event.packet
        if (packet is ClientboundEntityEventPacket &&
            packet.eventId == 35.toByte() &&
            packet.getEntity(level) === player
        ) {
            clock = delay.value.toInt()
        }
    }

    private fun findTotem(): Int {
        val player = Minecraft.getInstance().player ?: return -1
        val inventory = player.inventory
        for (slot in 0 until 9) {
            if (inventory.getItem(slot).item == Items.TOTEM_OF_UNDYING) return slot
        }
        for (slot in 9 until 36) {
            if (inventory.getItem(slot).item == Items.TOTEM_OF_UNDYING) return slot
        }
        return -1
    }

    companion object {
        fun isTotemInOffHand(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            return player.offhandItem.item == Items.TOTEM_OF_UNDYING
        }
    }
}
