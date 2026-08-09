package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.exp
import net.minecraft.world.level.Level

class ANAutoPlaceSlabs : ANBaseModule(
    name = "PlaceSlabs",
    description = "自动批量放置铺设台阶、铁轨方块",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动铺半砖"
), ANWorldRenderModule {
    val page = addSetting(ANSetting("Page", Page.MAIN))
    val placeXRange = addSetting(ANSetting("PlaceXRange", 2, 1, 4){page.value == Page.MAIN})
    val placeZRange = addSetting(ANSetting("PlaceZRange", 1, 0, 3){page.value == Page.MAIN})
    val airAllow = addSetting(ANSetting("AirAllow", false){page.value == Page.MAIN})
    val blocksPerTick = addSetting(ANSetting("BlocksPerTick", 6, 1, 16){page.value == Page.MAIN})

    val renderMode = addSetting(ANSetting("RenderMode", RenderMode.Fade){page.value == Page.RENDER})
    val renderRangeXZ = addSetting(ANSetting("RenderXY", 10, 10, 128){page.value == Page.RENDER})
    val renderRangeY = addSetting(ANSetting("RenderY", 10, 5, 128){page.value == Page.RENDER})
    val renderColor = addSetting(ANSetting("RenderColor", ColorGroupSetting(Color(0x7A00FF).rgb)){page.value == Page.RENDER})
    val height = addSetting(ANSetting("Height", 0.2f, 0.01f, 1f){page.value == Page.RENDER})

    enum class Page {
        MAIN,
        RENDER
    }

    private val renderBoxes = CopyOnWriteArrayList<AABB>()
    private var lastScanMs = 0L

    override fun onEnable() {
        renderBoxes.clear()
        lastScanMs = 0L
    }

    override fun onDisable() {
        renderBoxes.clear()
        Inventory.endSwap()
        Inventory.swapBack()
    }

    override fun onTick() {
        val player = mc.player ?: return
        if ((player.isDeadOrDying || player.health + player.absorptionAmount <= 0f)) {
            disable()
            return
        }

        placePlannedSlabs()
        val now = System.currentTimeMillis()
        if (now - lastScanMs >= 1000L) {
            renderBoxes.clear()
            renderBoxes.addAll(findSlabPlaceable())
            lastScanMs = now
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        val player = mc.player ?: return
        val boxes = renderBoxes
        if (boxes.isEmpty()) return

        val baseColor = ANColor.fromArgb(renderColor.value.getColor())
        for (box in boxes) {
            val alphaFactor = distanceAlpha(player.x, player.z, box.center.x, box.center.z)
            val line = baseColor.withAlpha((baseColor.alpha * alphaFactor).toInt().coerceIn(0, 255))
            val fill = line.withAlpha((60 * alphaFactor).toInt().coerceIn(0, 255))
            when (renderMode.value) {
                RenderMode.Fade, RenderMode.CubeBoth -> ANRender3DEngine.box(context, box, line, fill)
                RenderMode.CubeFill -> ANRender3DEngine.box(context, box, line.withAlpha(0), fill)
                RenderMode.CubeOutline -> ANRender3DEngine.box(context, box, line, null)
            }
        }
    }

    private fun placePlannedSlabs() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val slot = findSlabSlot()
        if (slot == Inventory.INVALID_SLOT) return

        val facing = player.direction
        val side = facing.clockWise
        val base = player.blockPosition()
        val targets = ArrayList<BlockPos>()

        for (front in 1..placeXRange.value) {
            val frontPos = base.relative(facing, front)
            for (lateral in -placeZRange.value..placeZRange.value) {
                val target = frontPos.relative(side, lateral)
                val below = target.below()
                if (!canPlaceAt(target)) continue
                if (airAllow.value || level.getBlockState(below).isCollisionShapeFullBlock(level, below)) {
                    targets += target
                }
            }
        }

        if (targets.isEmpty()) return
        val swapped = if (slot == player.inventory.selected) true else Inventory.swap(slot, swapBack = true)
        if (!swapped) return

        var placedAny = false
        try {
            targets.take(blocksPerTick.value).forEach {
                placeAt(it)
                placedAny = true
            }
        } finally {
            if (slot != player.inventory.selected) Inventory.swapBack()
            if (placedAny) {
                ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
            }
        }
    }

    private fun placeAt(pos: BlockPos) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val hit = supportHitResult(pos) ?: return

        
        val rotations = RotationUtil.getRotationsTo(player.eyePosition, hit.location)
        ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))

        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        player.swing(InteractionHand.MAIN_HAND)
    }

    private fun canPlaceAt(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        if (!level.isInWorldBounds(pos)) return false
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced()
    }

    private fun supportHitResult(pos: BlockPos): BlockHitResult? {
        val level = mc.level ?: return null
        val below = pos.below()
        if (level.getBlockState(below).canClick(level, below)) {
            return BlockHitResult(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), Direction.UP, below, false)
        }

        for (direction in Direction.entries) {
            val neighbor = pos.relative(direction.opposite)
            if (!level.getBlockState(neighbor).canClick(level, neighbor)) continue
            return BlockHitResult(Vec3.atCenterOf(neighbor), direction, neighbor, false)
        }
        return null
    }

    private fun findSlabSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val item = player.inventory.getItem(slot).item as? BlockItem ?: continue
            if (item.block is SlabBlock || item.block is BaseRailBlock) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun findSlabPlaceable(): List<AABB> {
        val player = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val center = player.blockPosition()
        val result = ArrayList<AABB>()

        for (x in center.x - renderRangeXZ.value..center.x + renderRangeXZ.value) {
            for (y in center.y - renderRangeY.value..center.y + renderRangeY.value) {
                for (z in center.z - renderRangeXZ.value..center.z + renderRangeXZ.value) {
                    val pos = BlockPos(x, y, z)
                    if (!level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) continue
                    val aboveState = level.getBlockState(pos.above())
                    val above2State = level.getBlockState(pos.above(2))
                    if ((!aboveState.isAir && !aboveState.canBeReplaced()) || (!above2State.isAir && !above2State.canBeReplaced())) continue
                    result += AABB(
                        pos.x.toDouble(),
                        pos.y + 1.0,
                        pos.z.toDouble(),
                        pos.x + 1.0,
                        pos.y + 1.0 + height.value,
                        pos.z + 1.0
                    )
                }
            }
        }

        return result
    }

    private fun distanceAlpha(playerX: Double, playerZ: Double, x: Double, z: Double): Float {
        val dx = x - playerX
        val dz = z - playerZ
        val factor = ((dx * dx + dz * dz) / renderRangeXZ.getPow2Value()).toFloat().coerceIn(0f, 1f)
        return (1f - easeOutExpo(factor)).coerceIn(0f, 1f)
    }

    private fun easeOutExpo(x: Float): Float {
        return if (x >= 1f) 1f else 1f - exp(-10f * x)
    }

    private fun BlockState.canClick(level: Level, pos: BlockPos): Boolean {
        return !isAir && !canBeReplaced() && isFaceSturdy(level, pos, Direction.UP)
    }

    enum class RenderMode {
        Fade,
        CubeFill,
        CubeOutline,
        CubeBoth
    }
}
