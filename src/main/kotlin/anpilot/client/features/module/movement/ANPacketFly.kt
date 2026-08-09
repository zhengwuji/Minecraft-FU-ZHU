package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.MoveEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import com.google.common.collect.Streams
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.phys.Vec3

class ANPacketFly : ANBaseModule(
    name = "PacketFly",
    description = "在空中自由悬浮飞行并穿越方块障碍",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "发包飞行"
) {
    val speed = addSetting(ANSetting("Speed", 0.3, 0.0, 5.0))
    val verticalSpeedMatch = addSetting(ANSetting("VSpeedSync", false))
    val antiKickMode = addSetting(ANSetting("Mode", AntiKickMode.NCP))

    enum class AntiKickMode {
        GrimOld,
        GrimNew,
        NCP,
        None
    }

    private var delayLeft = 20
    private var lastPacketY = Double.MAX_VALUE
    private var floatingTicks = 0

    private fun calculateGround(): Double {
        val player = mc.player ?: return 0.0
        val level = mc.level ?: return 0.0

        var ground = player.y
        while (ground > 0.0) {
            val box = player.boundingBox
            val adjustedBox = box.move(0.0, ground - player.y, 0.0)
            val blockCollisions = Streams.stream(level.getBlockCollisions(player, adjustedBox))
            if (blockCollisions.findAny().isPresent) return ground + 0.05
            ground -= 0.05
        }
        return 0.0
    }

    private fun isEntityOnAir(entity: Entity): Boolean {
        val level = mc.level ?: return true
        return level.getBlockStates(entity.boundingBox.inflate(0.0625).expandTowards(0.0, -0.55, 0.0))
            .allMatch(BlockBehaviour.BlockStateBase::isAir)
    }

    private fun shouldFlyDown(currentY: Double, lastY: Double): Boolean {
        return if (currentY >= lastY) true else lastY - currentY < 0.03130
    }

    private fun antiKickPacket(packet: ServerboundMovePlayerPacket, currentY: Double): ServerboundMovePlayerPacket {
        val player = mc.player ?: return packet
        
        if (this.delayLeft <= 0 && this.lastPacketY != Double.MAX_VALUE &&
            shouldFlyDown(currentY, this.lastPacketY) && isEntityOnAir(player)
        ) {
            val newY = lastPacketY - 0.03130
            lastPacketY = newY
            delayLeft = 20

            return if (packet.hasRotation()) {
                ServerboundMovePlayerPacket.PosRot(
                    packet.getX(0.0), newY, packet.getZ(0.0),
                    packet.getYRot(0f), packet.getXRot(0f),
                    packet.isOnGround
                )
            } else {
                ServerboundMovePlayerPacket.Pos(
                    packet.getX(0.0), newY, packet.getZ(0.0),
                    packet.isOnGround
                )
            }
        } else {
            lastPacketY = currentY
            if (!isEntityOnAir(player)) delayLeft = 20
        }
        if (delayLeft > 0) delayLeft--
        return packet
    }

    @ANEventHandler
    fun onSendPacket(event: PacketEvent.Send) {
        val player = mc.player ?: return
        if (player.vehicle != null || event.packet !is ServerboundMovePlayerPacket || antiKickMode.value != AntiKickMode.NCP)
            return

        val packet = event.packet as ServerboundMovePlayerPacket
        val currentY = packet.getY(Double.MAX_VALUE)
        val modifiedPacket: ServerboundMovePlayerPacket

        if (currentY != Double.MAX_VALUE) {
            modifiedPacket = antiKickPacket(packet, currentY)
        } else {
            val fullPacket = if (packet.hasRotation()) {
                ServerboundMovePlayerPacket.PosRot(
                    player.x, player.y, player.z,
                    packet.getYRot(0f), packet.getXRot(0f),
                    packet.isOnGround
                )
            } else {
                ServerboundMovePlayerPacket.Pos(
                    player.x, player.y, player.z,
                    packet.isOnGround
                )
            }
            modifiedPacket = antiKickPacket(fullPacket, player.y)
        }

        if (modifiedPacket !== packet) {
            event.setCancelled(true)
            mc.connection!!.send(modifiedPacket)
        }
    }

    @ANEventHandler
    fun onPlayerMove(event: MoveEvent) {
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (floatingTicks >= 20) {
            when (antiKickMode.value) {
                AntiKickMode.GrimNew -> {
                    val box = player.boundingBox
                    val adjustedBox = box.move(0.0, -0.4, 0.0)
                    val blockCollisions = Streams.stream(level.getBlockCollisions(player, adjustedBox))
                    if (!blockCollisions.findAny().isPresent) {
                        mc.connection!!.send(ServerboundMovePlayerPacket.Pos(player.x, player.y - 0.4, player.z, player.onGround()))
                        mc.connection!!.send(ServerboundMovePlayerPacket.Pos(player.x, player.y, player.z, player.onGround()))
                    }
                }
                AntiKickMode.GrimOld -> {
                    val box = player.boundingBox
                    val adjustedBox = box.move(0.0, -0.4, 0.0)
                    val blockCollisions = Streams.stream(level.getBlockCollisions(player, adjustedBox))
                    if (!blockCollisions.findAny().isPresent) {
                        val ground = calculateGround()
                        val groundExtra = ground + 0.1
                        var posY = player.y
                        while (posY > groundExtra) {
                            mc.connection!!.send(ServerboundMovePlayerPacket.Pos(player.x, posY, player.z, true))
                            if (posY - 4.0 < groundExtra) break
                            posY -= 4.0
                        }
                        mc.connection!!.send(ServerboundMovePlayerPacket.Pos(player.x, groundExtra, player.z, true))
                        posY = groundExtra
                        while (posY < player.y) {
                            mc.connection!!.send(ServerboundMovePlayerPacket.Pos(player.x, posY, player.z, player.onGround()))
                            if (posY + 4.0 > player.y) break
                            posY += 4.0
                        }
                        mc.connection!!.send(ServerboundMovePlayerPacket.Pos(player.x, player.y, player.z, player.onGround()))
                    }
                }
                else -> {}
            }
            floatingTicks = 0
        }

        val ySpeed = fullFlightMove(event, speed.value, verticalSpeedMatch.value)

        if (floatingTicks < 20) {
            if (ySpeed >= -0.1) {
                floatingTicks++
            } else if (antiKickMode.value == AntiKickMode.GrimNew) {
                floatingTicks = 0
            }
        }
    }

    fun fullFlightMove(event: MoveEvent, speed: Double, verticalSpeedMatch: Boolean): Double {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0.0

        var ySpeed = 0.0
        if (mc.options.keyJump.isDown) {
            ySpeed += speed
        }
        if (mc.options.keyShift.isDown) {
            ySpeed -= speed
        }

        var moveForward = 0f
        if (mc.options.keyUp.isDown) moveForward += 1f
        if (mc.options.keyDown.isDown) moveForward -= 1f

        var moveStrafe = 0f
        if (mc.options.keyLeft.isDown) moveStrafe += 1f
        if (mc.options.keyRight.isDown) moveStrafe -= 1f

        if (moveForward == 0f && moveStrafe == 0f) {
            player.deltaMovement = Vec3(0.0, ySpeed, 0.0)
        } else {
            if (moveForward != 0f) {
                if (moveStrafe > 0f) {
                    player.yRot += (if (moveForward > 0f) -45 else 45).toFloat()
                } else if (moveStrafe < 0f) {
                    player.yRot += (if (moveForward > 0f) 45 else -45).toFloat()
                }
                moveStrafe = 0f
                if (moveForward > 0f) {
                    moveForward = 1f
                } else if (moveForward < 0f) {
                    moveForward = -1f
                }
            }
            val rad = Math.toRadians((player.yRot + 90f).toDouble())
            val x = moveForward * speed * Math.cos(rad) + moveStrafe * speed * Math.sin(rad)
            val z = moveForward * speed * Math.sin(rad) - moveStrafe * speed * Math.cos(rad)
            player.deltaMovement = Vec3(x, ySpeed, z)
        }
        return ySpeed
    }
}
