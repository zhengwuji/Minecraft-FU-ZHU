package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PlayerUpdateEvent
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ItemSelectSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.max
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.level.material.Fluids

class ANNuker : ANBaseModule(
    name = "Nuker",
    description = "自动扫描并快速批量挖掘摧毁自身周围半径内的指定目标方块",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动爆拆"
), ANWorldRenderModule {

    val mode = addSetting(ANSetting("Mode", NukerMode.Default))
    val rotate = addSetting(ANSetting("Rotate", NukerRotateMode.Grim))
    val selection = addSetting(ANSetting("Selection", NukerSelection.ItemSelect))
    val selectedBlocks = addSetting(ANSetting("SelectedBlocks", ItemSelectSetting(listOf("stone", "cobblestone", "dirt", "sand"))))
    val range = addSetting(ANSetting("Range", 4.2f, 1.5f, 6.0f))
    val shape = addSetting(ANSetting("Shape", NukerShape.Sphere))
    val avoidLava = addSetting(ANSetting("AvoidLava", false))
    val sortMode = addSetting(ANSetting("SortMode", NukerSortMode.TopDown))

    private var currentBlock: BlockPos? = null
    private var pendingTarget: NukerTarget? = null


    enum class NukerMode {
        Default, Fast
    }

    enum class NukerSelection {
        All, ItemSelect
    }

    enum class NukerSortMode {
        None, Closest, TopDown
    }

    enum class NukerShape {
        Sphere, Cube
    }

    enum class NukerRotateMode {
        Off, Grim
    }


    override fun onEnable() {
        currentBlock = null
        pendingTarget = null
    }

    override fun onDisable() {
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
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        minecraft.gameMode ?: return

        pendingTarget = null
        val candidates = collectTargets()

        if (candidates.isEmpty()) {
            currentBlock = null
            return
        }

        val sortedBlocks = sortTargets(candidates, player)

        val target = sortedBlocks.first()
        currentBlock = target.pos
        pendingTarget = target
    }

    @ANEventHandler
    fun onUpdatePost(event: PlayerUpdateEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val gameMode = minecraft.gameMode ?: return
        val target = pendingTarget ?: return
        pendingTarget = null

        val state = level.getBlockState(target.pos)
        if (state.isAir || state.getDestroySpeed(level, target.pos) < 0f) {
            currentBlock = null
            return
        }
        if (player.eyePosition.distanceToSqr(target.hitVec) > range.value * range.value) {
            currentBlock = null
            return
        }

        val restoreRotation = if (rotate.value == NukerRotateMode.Grim) {
            Rotation(player.yRot, player.xRot)
        } else {
            null
        }

        if (!prepareRotationForAction(target)) return

        try {
            val packetMine = ANServiceRegistry.runtime.moduleManager.get("PacketMine") as? ANPacketMine
            if (packetMine != null && packetMine.enabled) {
                if (!packetMine.isMining(target.pos)) {
                    if (packetMine.hasFreeMine()) {
                        packetMine.startMining(target.pos, target.direction)
                    }
                }
                return
            }

            when (mode.value) {
                NukerMode.Fast -> {
                    val sortedBlocks = sortTargets(collectTargets(), player)

                    val fastTargets = if (player.abilities.instabuild) {
                        sortedBlocks.take(FAST_BLOCKS_PER_TICK)
                    } else {
                        sortedBlocks
                            .filter { canInstantBreak(it) }
                            .take(FAST_BLOCKS_PER_TICK)
                    }

                    if (fastTargets.isEmpty()) {
                        mineSingleTarget(target)
                        return
                    }

                    val fastTarget = fastTargets.first()
                    if (fastTarget != target && !prepareRotationForAction(fastTarget)) return

                    val bestSlot = findBestTool(level.getBlockState(fastTarget.pos))
                    if (bestSlot != -1 && bestSlot != player.inventory.selected) {
                        player.inventory.selected = bestSlot
                    }

                    if (player.abilities.instabuild) {
                        gameMode.destroyBlock(fastTarget.pos)
                    } else if (gameMode.startDestroyBlock(fastTarget.pos, fastTarget.direction)) {
                        gameMode.continueDestroyBlock(fastTarget.pos, fastTarget.direction)
                    }
                    player.swing(InteractionHand.MAIN_HAND)
                }
                NukerMode.Default -> {
                    mineSingleTarget(target)
                }
            }
        } finally {
            if (restoreRotation != null) {
                ANServiceRegistry.runtime.rotationManager.sendInstantRotation(
                    restoreRotation,
                    mouseSensitivityFix = false
                )
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        val line = ANColor(255, 80, 80, 180)
        val side = ANColor(255, 80, 80, 25)
        
        val block = currentBlock
        if (block != null) {
            ANRender3DEngine.box(context, AABB(block), line, side)
        }
    }

    private fun hasLavaNeighbor(pos: BlockPos): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        for (dir in Direction.values()) {
            val neighbor = pos.relative(dir)
            val state = level.getBlockState(neighbor)
            if (state.block == Blocks.LAVA) return true
            val fluid = state.fluidState
            if (fluid.type == Fluids.LAVA || fluid.type == Fluids.FLOWING_LAVA) {
                return true
            }
        }
        return false
    }

    private fun findBestTool(state: BlockState): Int {
        val player = Minecraft.getInstance().player ?: return -1
        var bestSlot = -1
        var maxSpeed = 1.0f
        for (slot in 0 until 9) {
            val stack: ItemStack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val speed = stack.getDestroySpeed(state)
            if (speed > maxSpeed) {
                maxSpeed = speed
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun mineSingleTarget(target: NukerTarget) {
        val player = Minecraft.getInstance().player ?: return
        val level = Minecraft.getInstance().level ?: return
        val gameMode = Minecraft.getInstance().gameMode ?: return

        val bestSlot = findBestTool(level.getBlockState(target.pos))
        if (bestSlot != -1 && bestSlot != player.inventory.selected) {
            player.inventory.selected = bestSlot
        }

        if (player.abilities.instabuild) {
            gameMode.destroyBlock(target.pos)
        } else {
            gameMode.continueDestroyBlock(target.pos, target.direction)
            player.swing(InteractionHand.MAIN_HAND)
        }
    }

    private fun canInstantBreak(target: NukerTarget): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val level = Minecraft.getInstance().level ?: return false
        val state = level.getBlockState(target.pos)
        val hardness = state.getDestroySpeed(level, target.pos)
        if (hardness < 0.0f) return false
        if (hardness == 0.0f) return true

        val bestSlot = findBestTool(state)
        val stack = if (bestSlot == -1) player.mainHandItem else player.inventory.getItem(bestSlot)
        val divisor = if (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) 30.0f else 100.0f
        val delta = stack.getDestroySpeed(state) / hardness / divisor
        return delta >= 1.0f
    }

    private fun sortTargets(targets: List<NukerTarget>, player: LocalPlayer): List<NukerTarget> {
        val movementYaw = movementYaw(player)
        return when (sortMode.value) {
            NukerSortMode.TopDown -> targets.sortedWith(
                compareBy<NukerTarget> { movementBlockerScore(it, player, movementYaw) }
                    .thenByDescending { it.pos.y }
                    .thenBy { player.eyePosition.distanceToSqr(it.hitVec) }
            )
            NukerSortMode.Closest -> targets.sortedWith(
                compareBy<NukerTarget> { movementBlockerScore(it, player, movementYaw) }
                    .thenBy { player.eyePosition.distanceToSqr(it.hitVec) }
            )
            NukerSortMode.None -> targets.sortedBy { movementBlockerScore(it, player, movementYaw) }
        }
    }

    private fun movementBlockerScore(
        target: NukerTarget,
        player: LocalPlayer,
        movementYaw: Float?
    ): Double {
        if (movementYaw == null) return NON_BLOCKER_SCORE
        val feetY = player.y.toInt()
        if (target.pos.y < feetY || target.pos.y > feetY + 1) return NON_BLOCKER_SCORE

        val yawRadians = Math.toRadians(movementYaw.toDouble())
        val motionX = -sin(yawRadians)
        val motionZ = cos(yawRadians)
        val movementBox = player.boundingBox
            .expandTowards(motionX * MOVEMENT_CLEAR_DISTANCE, 0.0, motionZ * MOVEMENT_CLEAR_DISTANCE)
            .inflate(MOVEMENT_CLEAR_MARGIN, 0.05, MOVEMENT_CLEAR_MARGIN)

        if (!AABB(target.pos).intersects(movementBox)) return NON_BLOCKER_SCORE

        val blockCenter = Vec3.atCenterOf(target.pos)
        val relativeX = blockCenter.x - player.x
        val relativeZ = blockCenter.z - player.z
        val forwardDistance = relativeX * motionX + relativeZ * motionZ
        val lateralDistance = abs(relativeX * motionZ - relativeZ * motionX)
        val heightPenalty = (target.pos.y - feetY).coerceAtLeast(0) * 0.35
        val forwardPenalty = max(0.0, forwardDistance) * 0.15
        return heightPenalty + forwardPenalty + lateralDistance * 0.25
    }

    private fun movementYaw(player: LocalPlayer): Float? {
        val options = Minecraft.getInstance().options
        val forward = when {
            options.keyUp.isDown && !options.keyDown.isDown -> 1
            options.keyDown.isDown && !options.keyUp.isDown -> -1
            else -> 0
        }
        val sideways = when {
            options.keyLeft.isDown && !options.keyRight.isDown -> 1
            options.keyRight.isDown && !options.keyLeft.isDown -> -1
            else -> 0
        }
        if (forward == 0 && sideways == 0) return null

        var yaw = player.yRot
        if (forward < 0) yaw += 180.0f
        val strafeFactor = when {
            forward < 0 -> -0.5f
            forward > 0 -> 0.5f
            else -> 1.0f
        }
        if (sideways > 0) yaw -= 90.0f * strafeFactor
        if (sideways < 0) yaw += 90.0f * strafeFactor
        return yaw
    }

    private fun collectTargets(): List<NukerTarget> {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return emptyList()
        val level = minecraft.level ?: return emptyList()
        val candidates = ArrayList<NukerTarget>()
        val r = (range.value + 1).toInt()
        val playerPos = player.blockPosition()
        val rangeSq = range.value * range.value
        val playerEye = player.eyePosition

        for (dx in -r..r) {
            for (dy in -r..r) {
                for (dz in -r..r) {
                    val pos = playerPos.offset(dx, dy, dz)
                    val state = level.getBlockState(pos)

                    if (state.isAir || state.getDestroySpeed(level, pos) < 0f) continue
                    if (state.block == Blocks.BEDROCK) continue
                    if (avoidLava.value && hasLavaNeighbor(pos)) continue
                    if (pos.y < player.y.toInt()) continue

                    if (shape.value == NukerShape.Sphere) {
                        if (playerEye.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue
                    } else if (shape.value == NukerShape.Cube) {
                        if (Math.abs(pos.x - playerPos.x) > range.value ||
                            Math.abs(pos.y - playerPos.y) > range.value ||
                            Math.abs(pos.z - playerPos.z) > range.value) continue
                    }

                    if (selection.value == NukerSelection.ItemSelect && !selectedBlocks.value.contains(state.block)) continue
                    val hitResult = findVisibleHit(pos) ?: continue
                    candidates.add(NukerTarget(pos.immutable(), hitResult.location, hitResult.direction))
                }
            }
        }
        return candidates
    }

    private fun findVisibleHit(pos: BlockPos): BlockHitResult? {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        val eye = player.eyePosition
        val preferredOffsets = arrayOf(
            Vec3(0.5, 0.5, 0.5),
            Vec3(0.5, 0.8, 0.5),
            Vec3(0.5, 0.2, 0.5),
            Vec3(0.2, 0.5, 0.5),
            Vec3(0.8, 0.5, 0.5),
            Vec3(0.5, 0.5, 0.2),
            Vec3(0.5, 0.5, 0.8)
        )

        for (offset in preferredOffsets) {
            visibleHit(pos, offset, eye)?.let { return it }
        }

        var bestHit: BlockHitResult? = null
        var bestScore = Double.MAX_VALUE
        var xOffset = 0.125
        while (xOffset <= 0.8751) {
            var yOffset = 0.125
            while (yOffset <= 0.8751) {
                var zOffset = 0.125
                while (zOffset <= 0.8751) {
                    val offset = Vec3(xOffset, yOffset, zOffset)
                    val hitResult = visibleHit(pos, offset, eye)
                    if (hitResult != null) {
                        val score = offset.distanceToSqr(CENTER_OFFSET) + eye.distanceToSqr(hitResult.location) * 0.001
                        if (score < bestScore) {
                            bestHit = hitResult
                            bestScore = score
                        }
                    }
                    zOffset += 0.25
                }
                yOffset += 0.25
            }
            xOffset += 0.25
        }
        return bestHit
    }

    private fun visibleHit(pos: BlockPos, offset: Vec3, eye: Vec3): BlockHitResult? {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        val hitVec = Vec3(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z)
        if (eye.distanceToSqr(hitVec) > range.value * range.value) return null

        val hitResult = level.clip(
            ClipContext(
                eye,
                hitVec,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        )
        return if (hitResult.type == HitResult.Type.BLOCK && hitResult.blockPos == pos) hitResult else null
    }

    private fun prepareRotationForAction(target: NukerTarget): Boolean {
        val rotation = rotationTo(target.hitVec)
        return when (rotate.value) {
            NukerRotateMode.Off -> true
            NukerRotateMode.Grim -> {
                ANServiceRegistry.runtime.rotationManager.sendInstantRotation(rotation)
                true
            }
        }
    }

    private fun rotationTo(vec: Vec3): Rotation {
        val player = Minecraft.getInstance().player ?: return Rotation(0f, 0f)
        val rotations = RotationUtil.getRotationsTo(player.eyePosition, vec)
        return Rotation(rotations[0], rotations[1])
    }

    private data class NukerTarget(
        val pos: BlockPos,
        val hitVec: Vec3,
        val direction: Direction
    )

    private companion object {
        const val FAST_BLOCKS_PER_TICK = 10
        const val MOVEMENT_CLEAR_DISTANCE = 1.35
        const val MOVEMENT_CLEAR_MARGIN = 0.08
        const val NON_BLOCKER_SCORE = 1000.0
        val CENTER_OFFSET: Vec3 = Vec3(0.5, 0.5, 0.5)
    }
}
