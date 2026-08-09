package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.EventPreSync
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.rotation.RotationUtil
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
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color

class ANFeetPlace : ANBaseModule(
    name = "FeetPlace",
    description = "自动在脚下及四周快速放置黑曜石进行自我防护",
    category = ANModuleCategory.COMBAT,
    chineseName = "脚部困人",
    defaultState = ANModuleState.DISABLED
), ANWorldRenderModule {
    val range = addSetting(ANSetting("Range", 4.0f, 1.0f, 6.0f))
    val extend = addSetting(ANSetting("Extend", false))
    val coverHead = addSetting(ANSetting("CoverHead", false))
    val floor = addSetting(ANSetting("Floor", false))

    val instantReplace = addSetting(ANSetting("Instant", false))
    val sequentialReplace = addSetting(ANSetting("Sequential", false))
    val attackSequential = addSetting(ANSetting("Attack", false) { sequentialReplace.value })

    val autoDisable = addSetting(ANSetting("AutoDisable", false))
    val rotate = addSetting(ANSetting("Rotate", RotateMode.SILENT))
    val autoSwap = addSetting(ANSetting("AutoSwap", true))
    val silentSwap = addSetting(ANSetting("SilentSwap", false) { autoSwap.value })
    val swapBack = addSetting(ANSetting("SwapBack", true) { autoSwap.value && !silentSwap.value })
    val placeDelay = addSetting(ANSetting("PlaceDelay", 50, 0, 500))
    val blocksPerTick = addSetting(ANSetting("BlocksPerTick", 1, 1, 4))

    val render = addSetting(ANSetting("Render", true))
    val renderColor = addSetting(ANSetting("RenderColor", ColorGroupSetting(Color(0x8835FF5E.toInt(), true).rgb)) {
        render.value
    })
    private val trapPositions = linkedSetOf<BlockPos>()
    private val queuedReplacements = linkedSetOf<BlockPos>()
    private var plannedPlacements = emptyList<PlacementData>()
    private var prevY = 0.0
    private var lastPlaceTime = 0L

    enum class RotateMode {
        OFF,
        NORMAL,
        SILENT
    }

    override fun onEnable() {
        mc.player?.let { prevY = it.y }
    }

    override fun onDisable() {
        trapPositions.clear()
        queuedReplacements.clear()
        plannedPlacements = emptyList()
        Inventory.endSwap()
        Inventory.swapBack()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        disable()
    }

    override fun onTick() {
        val player = mc.player ?: return clearPlan()
        if (mc.level == null || player.isSpectator || player.isCreative) return clearPlan()

        val dy = player.y - prevY
        if (autoDisable.value && (dy > 0.5 || dy < -1.5)) {
            disable()
            return
        }

        trapPositions.clear()
        trapPositions += buildTrapPositions()
        plannedPlacements = buildPlacementPlan()
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onPreSync(event: EventPreSync) {
        val placements = plannedPlacements
        if (placements.isEmpty()) return
        if (System.currentTimeMillis() - lastPlaceTime < placeDelay.value) return

        val slot = findObsidianSlot()
        if (slot == Inventory.INVALID_SLOT) return

        val limited = placements.take(blocksPerTick.value)
        rotateTo(event, limited.first().hit.location)
        if (placeAll(slot, limited)) {
            lastPlaceTime = System.currentTimeMillis()
            queuedReplacements.removeAll(limited.map { it.pos }.toSet())
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val packet = event.packet
        when (packet) {
            is ClientboundBlockUpdatePacket -> {
                if (instantReplace.value && packet.blockState.isAir && packet.pos in trapPositions) {
                    queuedReplacements += packet.pos
                }
            }

            is ClientboundAddEntityPacket -> {
                if (!sequentialReplace.value || packet.type != EntityType.END_CRYSTAL) return
                val pos = crystalTrapPosition(packet.x, packet.y, packet.z) ?: return
                if (attackSequential.value) {
                    attackEntityId(packet.id)
                }
                queuedReplacements += pos
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!render.value) return
        val color = renderColor.value.toANColor()
        val positions = if (plannedPlacements.isNotEmpty()) plannedPlacements.map { it.pos } else trapPositions
        positions.forEach { pos ->
            ANRender3DEngine.box(
                context,
                AABB(pos).inflate(0.015),
                color.withAlpha(255),
                color.withAlpha(35)
            )
        }
    }

    private fun buildTrapPositions(): Set<BlockPos> {
        val player = mc.player ?: return emptySet()
        val base = BlockPos.containing(player.x, player.boundingBox.minY + 0.1, player.z)
        val result = linkedSetOf<BlockPos>()

        val feet = Direction.Plane.HORIZONTAL.map { base.relative(it) }
        result += feet

        if (extend.value) {
            for (direction in Direction.Plane.HORIZONTAL) {
                val foot = base.relative(direction)
                if (shouldExtend(foot)) {
                    result += foot.relative(direction)
                }
            }
        }

        if (coverHead.value) {
            result += base.above(2)
        }

        if (floor.value) {
            result += base.below()
        }

        return result
    }

    private fun buildPlacementPlan(): List<PlacementData> {
        val positions = (queuedReplacements + trapPositions).toList().distinct()
        return positions
            .asSequence()
            .filter { canPlaceAt(it) }
            .mapNotNull { pos -> supportHitResult(pos)?.let { PlacementData(pos, it) } }
            .sortedWith(compareByDescending<PlacementData> { it.pos in queuedReplacements }.thenBy { distanceSq(it.pos) })
            .toList()
    }

    private fun shouldExtend(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced() || !state.`is`(Blocks.OBSIDIAN)
    }

    private fun canPlaceAt(pos: BlockPos): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) > range.value * range.value) return false
        if (!level.isInWorldBounds(pos)) return false
        val state = level.getBlockState(pos)
        if (!state.canBeReplaced()) return false
        if (hasEntityBlocking(AABB(pos))) return false
        return supportHitResult(pos) != null
    }

    private fun supportHitResult(pos: BlockPos): BlockHitResult? {
        val level = mc.level ?: return null
        for (direction in Direction.entries) {
            val neighbor = pos.relative(direction.opposite)
            val state = level.getBlockState(neighbor)
            if (!canClick(state)) continue
            return BlockHitResult(Vec3.atCenterOf(neighbor), direction, neighbor, false)
        }
        return null
    }

    private fun canClick(state: BlockState): Boolean {
        return !state.isAir && !state.canBeReplaced()
    }

    private fun hasEntityBlocking(box: AABB): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        return level.getEntities(player, box) { entity ->
            !entity.isRemoved && entity.type != EntityType.EXPERIENCE_ORB
        }.isNotEmpty()
    }

    private fun placeAll(slot: Int, placements: List<PlacementData>): Boolean {
        val player = mc.player ?: return false
        val swapped = when {
            slot == player.inventory.selected -> true
            silentSwap.value -> Inventory.startSwap(slot)
            autoSwap.value -> Inventory.swap(slot, swapBack.value)
            else -> false
        }
        if (!swapped) return false

        var placed = false
        try {
            for (placement in placements) {
                interact(placement.hit)
                placed = true
            }
        } finally {
            if (slot != player.inventory.selected) {
                if (silentSwap.value) {
                    Inventory.endSwap()
                } else if (autoSwap.value && swapBack.value) {
                    Inventory.swapBack()
                }
            }
        }
        return placed
    }

    private fun interact(hit: BlockHitResult) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        player.swing(InteractionHand.MAIN_HAND)
    }

    private fun rotateTo(event: EventPreSync, vec: Vec3) {
        val player = mc.player ?: return
        val mode = rotate.value
        if (mode == RotateMode.OFF) return

        val rotations = RotationUtil.getRotationsTo(player.eyePosition, vec)
        val oldYaw = player.yRot
        val oldPitch = player.xRot

        when (mode) {
            RotateMode.NORMAL -> {
                player.yRot = rotations[0]
                player.xRot = rotations[1]
                player.yHeadRot = rotations[0]
            }

            RotateMode.SILENT -> {
                player.yRot = rotations[0]
                player.xRot = rotations[1]
                player.yHeadRot = rotations[0]

                val previousPostAction = event.postAction
                event.postAction = Runnable {
                    previousPostAction?.run()
                    player.yRot = oldYaw
                    player.xRot = oldPitch
                    player.yHeadRot = oldYaw
                }
            }

            RotateMode.OFF -> Unit
        }
    }

    private fun findObsidianSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.OBSIDIAN)) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun crystalTrapPosition(x: Double, y: Double, z: Double): BlockPos? {
        val crystalPos = BlockPos.containing(x, y, z)
        if (crystalPos in trapPositions) return crystalPos
        val below = crystalPos.below()
        if (below in trapPositions) return below
        return trapPositions.firstOrNull { AABB(it).inflate(0.75).contains(Vec3(x, y, z)) }
    }

    private fun attackEntityId(entityId: Int) {
        val connection = mc.connection ?: return
        val entity = mc.level?.getEntity(entityId) ?: return
        connection.send(ServerboundInteractPacket.createAttackPacket(entity, mc.player?.isShiftKeyDown ?: false))
        connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
    }

    private fun distanceSq(pos: BlockPos): Double {
        val player = mc.player ?: return Double.MAX_VALUE
        return player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos))
    }

    private fun clearPlan() {
        plannedPlacements = emptyList()
        trapPositions.clear()
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class PlacementData(val pos: BlockPos, val hit: BlockHitResult)
}
