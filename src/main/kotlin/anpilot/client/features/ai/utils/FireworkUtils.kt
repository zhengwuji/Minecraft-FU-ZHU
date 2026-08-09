package anpilot.client.features.ai.utils

import anpilot.client.features.ai.agent.ANAgent
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items

object FireworkUtils {
    fun useFirework(): Boolean {
        val minecraft = ANAgent.minecraft
        val player = minecraft.player ?: return false
        val gameMode = minecraft.gameMode ?: return false
        val connection = minecraft.connection ?: return false
        val inventory = player.inventory
        val originalSlot = inventory.selected
        var fireworkSlot = -1
        for (slot in 0 until 9) {
            if (inventory.getItem(slot).item == Items.FIREWORK_ROCKET) {
                fireworkSlot = slot
                break
            }
        }
        if (fireworkSlot == -1) return false

        inventory.selected = fireworkSlot
        connection.send(ServerboundSetCarriedItemPacket(fireworkSlot))
        gameMode.useItem(player, InteractionHand.MAIN_HAND)
        connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        if (originalSlot != fireworkSlot) {
            inventory.selected = originalSlot
            connection.send(ServerboundSetCarriedItemPacket(originalSlot))
        }
        return true
    }
}
