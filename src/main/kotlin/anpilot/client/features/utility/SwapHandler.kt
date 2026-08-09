package anpilot.client.features.utility

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket

class SwapHandler {
    private val mc = Minecraft.getInstance()
    private var previousSlot = -1

    fun startSwap(slot: Int) {
        previousSlot = mc.player?.inventory?.selected ?: 0
        mc.player?.connection?.send(ServerboundSetCarriedItemPacket(slot))
    }

    fun endSwap() {
        if (previousSlot != -1) {
            mc.player?.connection?.send(ServerboundSetCarriedItemPacket(previousSlot))
            previousSlot = -1
        }
    }
}
