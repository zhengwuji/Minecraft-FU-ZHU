package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import java.util.ArrayDeque
import java.util.UUID
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.world.entity.Entity

class ANBlink : ANBaseModule(
    name = "Blink",
    description = "暂时挂起移动，关闭时瞬间传送至当前位置",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "瞬移"
) {
    val limit = addSetting(ANSetting("Limit", 0, 0, 500))

    private val packets = ArrayDeque<ServerboundMovePlayerPacket>()
    private var fakePlayer: FakePlayerEntity? = null

    override fun getDisplayHudName(): String {
        val baseName = super.getDisplayHudName()
        val limitVal = limit.value
        return if (limitVal == 0) {
            "$baseName [${packets.size}]"
        } else {
            "$baseName [${packets.size}/$limitVal]"
        }
    }

    override fun onEnable() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        fakePlayer = FakePlayerEntity(player, level)
    }

    override fun onDisable() {
        val player = mc.player ?: return
        fakePlayer?.despawn()
        fakePlayer = null

        packets.forEach { player.connection.send(it) }
        packets.clear()
    }

    override fun onTick() {
        val limitVal = limit.value
        if (limitVal == 0) return

        if (packets.size >= limitVal) {
            disable()
            enable()
        }
    }

    @ANEventHandler
    fun onPacketSend(event: PacketEvent.Send) {
        val packet = event.packet
        if (packet !is ServerboundMovePlayerPacket) return

        event.cancel()

        val prevPacket = packets.peekLast()
        if (prevPacket != null &&
            packet.isOnGround == prevPacket.isOnGround &&
            packet.getYRot(-1.0f) == prevPacket.getYRot(-1.0f) &&
            packet.getXRot(-1.0f) == prevPacket.getXRot(-1.0f) &&
            packet.getX(-1.0) == prevPacket.getX(-1.0) &&
            packet.getY(-1.0) == prevPacket.getY(-1.0) &&
            packet.getZ(-1.0) == prevPacket.getZ(-1.0)
        ) {
            return
        }

        packets.addLast(packet)
    }

    fun cancelBlink() {
        val player = mc.player ?: return
        packets.clear()
        fakePlayer?.resetPlayerPosition(player)
        disable()
    }

    private class FakePlayerEntity(player: LocalPlayer, level: ClientLevel) : RemotePlayer(level, player.gameProfile) {
        init {
            uuid = UUID.randomUUID()
            setPos(player.x, player.y, player.z)
            yRot = player.yRot
            xRot = player.xRot
            yHeadRot = player.yHeadRot
            yBodyRot = player.yBodyRot

            val modelParts = player.entityData.get(DATA_PLAYER_MODE_CUSTOMISATION)
            entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, modelParts)

            inventory.replaceWith(player.inventory)

            unsetRemoved()
            level.addPlayer(this.id, this)
        }

        override fun getPlayerInfo(): PlayerInfo? {
            return Minecraft.getInstance().connection?.getPlayerInfo(gameProfile.id)
        }

        override fun doPush(entity: Entity) {
            
        }

        fun despawn() {
            discard()
        }

        fun resetPlayerPosition(player: LocalPlayer) {
            player.setPos(getX(), getY(), getZ())
            player.yRot = yRot
            player.xRot = xRot
        }
    }
}
