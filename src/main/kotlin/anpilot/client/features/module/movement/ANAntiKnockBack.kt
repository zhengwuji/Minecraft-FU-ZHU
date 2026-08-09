package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.event.impl.EntityVelocityUpdateEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.utility.system.IExplosionS2CPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.core.BlockPos

class ANAntiKnockBack : ANBaseModule(
    name = "AntiKnockBack",
    description = "消除或自定义缩减玩家受到攻击、爆炸及鱼钩拉拽时的击退效果",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "反击退"
) {
    val mode = addSetting(ANSetting("Mode", VelocityMode.NORMAL))
    val cancelKnockback = addSetting(ANSetting("Knockback", true))
    val horizontal = addSetting(ANSetting("Horizontal", 0f, 0f, 100f) { mode.value == VelocityMode.NORMAL })
    val vertical = addSetting(ANSetting("Vertical", 0f, 0f, 100f) { mode.value == VelocityMode.NORMAL })
    val noPushLiquids = addSetting(ANSetting("Liquid", false))

    enum class VelocityMode { NORMAL, WALL, GRIM_V2}

    private var concealVelocity = false
    private var needReset = false

    override fun onDisable() {
        concealVelocity = false
        needReset = false
    }

    @ANEventHandler
    fun onVelocity(event: EntityVelocityUpdateEvent) {
        val player = mc.player ?: return
        if (event.entity != null && event.entity !== player) return

        if (concealVelocity && event.x == 0.0 && event.y == 0.0 && event.z == 0.0) {
            concealVelocity = false
            return
        }

        if (!cancelKnockback.value) return

        when (mode.value) {
            VelocityMode.NORMAL -> {
                event.x *= horizontal.value / 100.0
                event.y *= vertical.value / 100.0
                event.z *= horizontal.value / 100.0
            }
            VelocityMode.GRIM_V2 -> {
                if (isStatic(player) || event.x != 0.0 || event.z != 0.0) {
                    needReset = true
                }
                event.x = 0.0
                event.z = 0.0
            }
            VelocityMode.WALL -> {
                if (isInsideBlock(player)) {
                    event.x = 0.0
                    event.z = 0.0
                }
            }
        }
    }

    override fun onTick() {
        val player = mc.player ?: return
        val connection = mc.connection ?: return
        if (mode.value != VelocityMode.GRIM_V2 || !needReset) return

        connection.send(
            ServerboundMovePlayerPacket.PosRot(
                player.x, player.y, player.z,
                player.yRot, player.xRot,
                player.onGround()
            )
        )
        connection.send(
            ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                player.blockPosition().below(),
                Direction.UP
            )
        )
        needReset = false
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val packet = event.packet

        if (packet is ClientboundExplodePacket) {
            if (shouldCancelExplosions()) {
                event.cancel()
            } else if (mode.value == VelocityMode.NORMAL) {
                IExplosionS2CPacket::class.java.cast(packet).apply {
                    ANPilotSetVelocityX((packet.knockbackX * horizontal.value / 100f))
                    ANPilotSetVelocityY((packet.knockbackY * vertical.value / 100f))
                    ANPilotSetVelocityZ((packet.knockbackZ * horizontal.value / 100f))
                }
            }
        }

        if (packet is ClientboundPlayerPositionPacket) {
            concealVelocity = true
        }

        if (packet is ClientboundEntityEventPacket) {
            if (packet.eventId.toInt() == 31) {
                val entity = packet.getEntity(level)
                if (entity is FishingHook && entity.hookedIn == player) {
                    event.cancel()
                }
            }
        }
    }

    private fun shouldCancelExplosions(): Boolean {
        val player = mc.player ?: return true
        return when (mode.value) {
            VelocityMode.WALL-> isInsideBlock(player)
            VelocityMode.NORMAL -> horizontal.value == 0f && vertical.value == 0f
            else -> true
        }
    }

    fun shouldCancelFishhook(): Boolean = enabled

    fun shouldCancelEntityPush(): Boolean = enabled

    fun shouldCancelBlockPush(): Boolean = enabled

    fun shouldCancelLiquidPush(): Boolean = enabled && noPushLiquids.value

    private fun isStatic(player: LocalPlayer): Boolean {
        val delta = player.deltaMovement
        return delta.x == 0.0 && player.onGround() && delta.z == 0.0
    }

    private fun isInsideBlock(entity: Entity): Boolean {
        val level = mc.level ?: return false
        val box = entity.boundingBox
        val minX = Math.floor(box.minX).toInt()
        val minY = Math.floor(box.minY).toInt()
        val minZ = Math.floor(box.minZ).toInt()
        val maxX = Math.floor(box.maxX).toInt()
        val maxY = Math.floor(box.maxY).toInt()
        val maxZ = Math.floor(box.maxZ).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    val shape = state.getCollisionShape(level, pos)
                    if (!shape.isEmpty) {
                        val shapeBox = shape.bounds().move(pos)
                        if (box.intersects(shapeBox)) return true
                    }
                }
            }
        }
        return false
    }
}
