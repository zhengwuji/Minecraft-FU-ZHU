package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANTickEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.utility.ANTimer
import anpilot.client.features.utility.DamageableFakePlayer
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.world.entity.Pose
import net.minecraft.world.phys.Vec3
import java.util.Optional

class ANFakePlayer : ANBaseModule(
    name = "FakePlayer",
    description = "生成一个假人实体进行伤害测试",
    category = ANModuleCategory.MISC,
    chineseName = "假人"
) {
    private val crawlingPose = addSetting(ANSetting("Crawling", false))
    private val record = addSetting(ANSetting("Record", false))
    private val play = addSetting(ANSetting("Play", false))

    private var fakePlayer: DamageableFakePlayer? = null
    private var clear = false
    private var index = 0

    private val positions = mutableListOf<PositionShot>()
    private val gappleTimer = ANTimer()

    override fun onEnable() {
        val player = mc.player
        if (player != null) {
            fakePlayer = DamageableFakePlayer(player, mc.user.name + "_Bot").apply {
                spawnPlayer()
            }
        }
    }

    override fun onDisable() {
        fakePlayer?.let {
            if (!it.isRemoved) {
                it.despawnPlayer()
            }
        }
        fakePlayer = null
    }

    @ANEventHandler
    fun onTick(event: ANTickEvent) {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            if (isOn()) {
                disable()
            }
            return
        }

        val fp = fakePlayer ?: return

        fp.pose = if (crawlingPose.value) Pose.SWIMMING else Pose.STANDING
        fp.baseTick()

        if (gappleTimer.every(1600)) {
            fp.simulateGappleEat()
        }

        if (play.value) {
            record.setValue(false)
            if (positions.isEmpty()) {
                play.setValue(false)
                return
            }

            if (index >= positions.size) {
                index = 0
            }

            val shot = positions[index++]
            fp.setPosRaw(shot.x, shot.y, shot.z)
            fp.setYRot(shot.yaw)
            fp.setXRot(shot.pitch)
            fp.yHeadRot = shot.headYaw
            fp.yBodyRot = shot.bodyYaw
            fp.deltaMovement = shot.velocity
        } else if (record.value) {
            if (!clear) {
                clear = true
                positions.clear()
            }
            play.setValue(false)
            fp.deltaMovement = Vec3.ZERO
            snapPosition()
        } else if (clear) {
            clear = false
        }
    }

    @ANEventHandler
    fun onPacketOutbound(event: PacketEvent.Send) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val fp = fakePlayer ?: return

        val packet = event.packet
        if (packet is ServerboundInteractPacket) {
            val attacked = level.entitiesForRendering().find { it == fp }
            if (attacked != null) {
                fp.simulateAttackFrom(player)
            }
        }
    }

    @ANEventHandler
    fun onPacketInbound(event: PacketEvent.Receive) {
        val fp = fakePlayer ?: return
        val packet = event.packet
        if (packet is ClientboundExplodePacket) {
            fp.simulateExplosionFrom(Vec3(packet.x, packet.y, packet.z))
        }
    }

    private fun snapPosition() {
        val player = mc.player ?: return
        positions.add(
            PositionShot(
                x = player.x,
                y = player.y,
                z = player.z,
                yaw = player.yRot,
                headYaw = player.yHeadRot,
                bodyYaw = player.yBodyRot,
                pitch = player.xRot,
                velocity = player.deltaMovement
            )
        )
    }

    data class PositionShot(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val headYaw: Float,
        val bodyYaw: Float,
        val pitch: Float,
        val velocity: Vec3
    )
}