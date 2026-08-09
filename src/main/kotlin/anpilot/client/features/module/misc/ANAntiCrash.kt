package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket

class ANAntiCrash : ANBaseModule(
    name = "AntiCrash",
    description = "防止恶意玩家炸客户端",
    category = ANModuleCategory.MISC,
    chineseName = "防崩服",
    defaultState = ANModuleState.ENABLED
) {

    @ANEventHandler
    fun onReceivePacket(event: PacketEvent.Receive) {
        when (val packet = event.packet) {
            is ClientboundExplodePacket -> {
                val x = packet.x
                val y = packet.y
                val z = packet.z
                val kx = packet.knockbackX.toDouble()
                val ky = packet.knockbackY.toDouble()
                val kz = packet.knockbackZ.toDouble()

                if (x > 30_000_000 || y > 30_000_000 || z > 30_000_000 ||
                    x < -30_000_000 || y < -30_000_000 || z < -30_000_000 ||
                    kx > 30_000_000 || ky > 30_000_000 || kz > 30_000_000 ||
                    kx < -30_000_000 || ky < -30_000_000 || kz < -30_000_000
                ) {
                    cancelPacket(event)
                }
            }
            is ClientboundLevelParticlesPacket -> {
                if (packet.count > 100_000) {
                    cancelPacket(event)
                }
            }
            is ClientboundPlayerPositionPacket -> {
                val x = packet.x
                val y = packet.y
                val z = packet.z
                if (x > 30_000_000 || y > 30_000_000 || z > 30_000_000 ||
                    x < -30_000_000 || y < -30_000_000 || z < -30_000_000
                ) {
                    cancelPacket(event)
                }
            }
            is ClientboundSetEntityMotionPacket -> {
                val xa = packet.xa
                val ya = packet.ya
                val za = packet.za
                if (xa > 100_000 || ya > 100_000 || za > 100_000 ||
                    xa < -100_000 || ya < -100_000 || za < -100_000
                ) {
                    cancelPacket(event)
                }
            }
        }
    }

    private fun cancelPacket(event: PacketEvent.Receive) {
        sendClientMessage("有坏蛋正在崩服！！！")
        event.cancel()
    }
}
