package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PlayerUpdateEvent
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationPriority
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth

class ANTunnelMiner : ANBaseModule(
    name = "TunnelMiner",
    description = "自动向前开掘指定尺寸的矿道隧道",
    category = ANModuleCategory.PLAYER,
    chineseName = "隧道开挖"
), ANWorldRenderModule {

    val width = addSetting(ANSetting("Width", 1f, 1f, 5f))
    val height = addSetting(ANSetting("Height", 2f, 1f, 5f))
    val limit = addSetting(ANSetting("Distance", 0f, 0f, 1000f))

    val lineColor = addSetting(ANSetting("LineColor", ColorGroupSetting(Color(55, 55, 255, 250).rgb)))
    val sideColor = addSetting(ANSetting("FillColor", ColorGroupSetting(Color(9, 255, 0, 60).rgb)))
    val activeLineColor = addSetting(ANSetting("MineLineColor", ColorGroupSetting(Color(255, 0, 0, 60).rgb)))
    val activeSideColor = addSetting(ANSetting("MineFillColor", ColorGroupSetting(Color(255, 0, 0, 40).rgb)))

    private var start: BlockPos? = null
    private var direction: Direction? = null
    private var length = 0
    private var currentBlock: BlockPos? = null
    private var pendingTarget: TunnelTarget? = null
    private var ticks = 0

    override fun onEnable() {
        val player = Minecraft.getInstance().player ?: return
        start = player.blockPosition()
        direction = player.direction
        length = 0
        currentBlock = null
        pendingTarget = null
        ticks = 0

    }

    override fun onDisable() {
        mc.options.keyUp.isDown = false
        if (currentBlock != null) {
            mc.gameMode?.stopDestroyBlock()
            currentBlock = null
        }
        pendingTarget = null
    }

    override fun onTick() {
    }

    @ANEventHandler
    fun onUpdatePre(event: PlayerUpdateEvent.Pre) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val gameMode = mc.gameMode ?: return

        pendingTarget = null
        ticks++

        val maxLen = limit.value.toInt()
        if (maxLen > 0 && length >= maxLen) {
            sendClientMessage("挖掘完成！")
            setEnabled(false)
            return
        }

        if (hasFallingBlocksNearby()) {
            mc.options.keyUp.isDown = false
            return
        }

        val sliceBlocks = getBlocksInSlice(length)
        
        val blocksToDig = sliceBlocks.filter { pos ->
            val state = level.getBlockState(pos)
            !state.isAir && state.getDestroySpeed(level, pos) >= 0f
        }

        if (blocksToDig.isEmpty()) {
            currentBlock = null
            gameMode.stopDestroyBlock()
            length++
            mc.options.keyUp.isDown = false
            return
        }

        val target = blocksToDig.first()
        val hitResult = findVisibleHit(target)
        val targetHit = hitResult?.location ?: Vec3.atCenterOf(target)
        val targetDirection = hitResult?.direction ?: getVisibleDirection(target)
        val distSq = player.eyePosition.distanceToSqr(targetHit)
        if (distSq > 16.0) { 
            faceTunnelDirection(player)
            mc.options.keyUp.isDown = true
            return
        }

        mc.options.keyUp.isDown = false 
        currentBlock = target

        val rotations = getNeededRotations(targetHit)
        val rotation = Rotation(rotations[0], rotations[1])
        pendingTarget = TunnelTarget(target.immutable(), targetHit, targetDirection, rotation)
        ANServiceRegistry.runtime.rotationManager.requestRotation(
            rotation,
            RotationPriority.INTERACTION,
            "TunnelMiner"
        )
    }

    @ANEventHandler
    fun onUpdatePost(event: PlayerUpdateEvent.Post) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val gameMode = mc.gameMode ?: return
        val target = pendingTarget ?: return
        pendingTarget = null

        val state = level.getBlockState(target.pos)
        if (state.isAir || state.getDestroySpeed(level, target.pos) < 0f) {
            currentBlock = null
            return
        }
        if (player.eyePosition.distanceToSqr(target.hitVec) > 16.0) {
            currentBlock = null
            return
        }

        if (!ANServiceRegistry.runtime.rotationManager.isRotationReached(target.rotation)) {
            ANServiceRegistry.runtime.rotationManager.sendInstantRotation(target.rotation)
        }

        val packetMine = ANServiceRegistry.runtime.moduleManager.get("PacketMine") as? ANPacketMine
        if (packetMine != null && packetMine.enabled) {
            if (!packetMine.isMining(target.pos)) {
                if (packetMine.hasFreeMine()) {
                    packetMine.startMining(target.pos, target.direction)
                }
            }
            return
        }

        gameMode.continueDestroyBlock(target.pos, target.direction)
        player.swing(InteractionHand.MAIN_HAND)
    }

    override fun renderWorld(context: LevelRenderContext) {
        val startPos = start ?: return
        val dir = direction ?: return
        
        val sliceBlocks = getBlocksInSlice(length)
        val line = lineColor.value.toANColor()
        val side = sideColor.value.toANColor()
        val activeLine = activeLineColor.value.toANColor()
        val activeSide = activeSideColor.value.toANColor()
        
        for (pos in sliceBlocks) {
            val box = AABB(pos)
            if (pos == currentBlock) {
                ANRender3DEngine.box(context, box, activeLine, activeSide)
            } else {
                ANRender3DEngine.box(context, box, line, side)
            }
        }
    }

    private fun getBlocksInSlice(len: Int): List<BlockPos> {
        val list = ArrayList<BlockPos>()
        val startPos = start ?: return list
        val dir = direction ?: return list
        val leftDir = dir.counterClockWise
        val w = width.value.toInt()
        val h = height.value.toInt()

        val minLeft = -(w - 1) / 2
        val maxLeft = w / 2
        val minY = 0
        val maxY = h - 1

        for (l in minLeft..maxLeft) {
            for (y in minY..maxY) {
                val pos = startPos.relative(dir, len)
                    .relative(leftDir, l)
                    .above(y)
                list.add(pos)
            }
        }
        return list.sortedByDescending { it.y }
    }

    private fun faceTunnelDirection(player: LocalPlayer) {
        val targetRot = direction?.toYRot() ?: player.yRot
        ANServiceRegistry.runtime.rotationManager.requestRotation(
            Rotation(targetRot, player.xRot),
            RotationPriority.INTERACTION,
            "TunnelMiner"
        )
    }

    private fun hasFallingBlocksNearby(): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        
        for (entity in level.entitiesForRendering()) {
            if (entity is FallingBlockEntity) {
                if (player.distanceToSqr(entity) < 36.0) {
                    return true
                }
            }
        }
        return false
    }

    private fun getNeededRotations(vec: Vec3): FloatArray {
        val player = Minecraft.getInstance().player ?: return floatArrayOf(0f, 0f)
        val eyesPos = player.eyePosition
        val diffX = vec.x - eyesPos.x
        val diffY = vec.y - eyesPos.y
        val diffZ = vec.z - eyesPos.z
        val diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ)
        val yaw = Math.toDegrees(Math.atan2(diffZ, diffX)).toFloat() - 90f
        val pitch = (-Math.toDegrees(Math.atan2(diffY, diffXZ))).toFloat()
        return floatArrayOf(
            player.yRot + Mth.wrapDegrees(yaw - player.yRot),
            player.xRot + Mth.wrapDegrees(pitch - player.xRot)
        )
    }

    private fun findVisibleHit(pos: BlockPos): BlockHitResult? {
        val player = Minecraft.getInstance().player ?: return null
        val level = Minecraft.getInstance().level ?: return null
        val eye = player.eyePosition

        var xOffset = 0.0
        while (xOffset <= 1.0001) {
            var yOffset = 0.0
            while (yOffset <= 1.0001) {
                var zOffset = 0.0
                while (zOffset <= 1.0001) {
                    val hitVec = Vec3(pos.x + xOffset, pos.y + yOffset, pos.z + zOffset)
                    if (eye.distanceToSqr(hitVec) <= 16.0) {
                        val hitResult = level.clip(
                            ClipContext(
                                eye,
                                hitVec,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                player
                            )
                        )
                        if (hitResult.type == HitResult.Type.BLOCK && hitResult.blockPos == pos) {
                            return hitResult
                        }
                    }
                    zOffset += 0.2
                }
                yOffset += 0.2
            }
            xOffset += 0.2
        }
        return null
    }

    private fun getVisibleDirection(pos: BlockPos): Direction {
        val player = Minecraft.getInstance().player ?: return Direction.UP
        val dx = player.x - (pos.x + 0.5)
        val dy = player.eyePosition.y - (pos.y + 0.5)
        val dz = player.z - (pos.z + 0.5)
        
        val absX = Math.abs(dx)
        val absY = Math.abs(dy)
        val absZ = Math.abs(dz)
        
        return if (absX > absY && absX > absZ) {
            if (dx > 0) Direction.EAST else Direction.WEST
        } else if (absY > absX && absY > absZ) {
            if (dy > 0) Direction.UP else Direction.DOWN
        } else {
            if (dz > 0) Direction.SOUTH else Direction.NORTH
        }
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class TunnelTarget(
        val pos: BlockPos,
        val hitVec: Vec3,
        val direction: Direction,
        val rotation: Rotation
    )
}
