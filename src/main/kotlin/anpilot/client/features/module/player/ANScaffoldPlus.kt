package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.rotation.Rotation
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

class ANScaffoldPlus : ANBaseModule(
    name = "Scaffold",
    description = "自动在脚下铺路搭桥，防止掉落",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动搭桥"
), ANWorldRenderModule {
    val width = addSetting(ANSetting("Width", 1, 1, 5))
    val usingPause = addSetting(ANSetting("UsingPause", true))
    val sideColor = addSetting(ANSetting("FillColor", ColorGroupSetting(Color(255, 255, 255, 50).rgb)))
    val lineColor = addSetting(ANSetting("OutLine", ColorGroupSetting(Color(255, 255, 255, 255).rgb)))

    private val renderPositions = CopyOnWriteArrayList<BlockPos>()
    private var placeCooldown = 0

    override fun onDisable() {
        renderPositions.clear()
        placeCooldown = 0
        Inventory.swapBack()
        Inventory.endSwap()
    }

    override fun onTick() {
        val player = mc.player ?: return clearRender()
        val level = mc.level ?: return clearRender()
        if (usingPause.value && player.isUsingItem) return clearRender()
        if (placeCooldown > 0) {
            placeCooldown--
            return
        }

        val playerPos = player.blockPosition()
        val slabMode = isSlab(playerPos) || Direction.Plane.HORIZONTAL.any { isSlab(playerPos.relative(it)) }
        val slot = if (slabMode) findSlabSlot() else findBlockSlot(allowSlab = true)
        if (slot == Inventory.INVALID_SLOT) return clearRender()

        val facing = player.direction
        val side = facing.clockWise
        
        
        val centerPos = if (slabMode) {
            var p = playerPos
            if (isSlab(playerPos)) {
                p = playerPos.relative(facing)
            } else if (clientCanPlace(p) && placeSide(p) == null) {
                p = bestAdjacentPlacePos(p) ?: return clearRender()
            }
            if (isSlab(p)) return clearRender()
            p
        } else {
            var p = playerPos.below()
            if (!clientCanPlace(p)) return clearRender()
            if (placeSide(p) == null) {
                p = bestAdjacentPlacePos(p) ?: return clearRender()
            }
            p
        }

        val plans = createPlacementPlans(centerPos, side, slot, slabMode)

        if (plans.isEmpty()) return clearRender()

        renderPositions.clear()
        renderPositions.addAll(plans.map { it.pos })

        val swapped = if (slot == player.inventory.selected) true else Inventory.swap(slot, swapBack = true)
        if (!swapped) return

        try {
            var blockCount = player.inventory.getItem(slot).count
            for (plan in plans) {
                if (blockCount <= 0) break
                rotateTo(plan.hit.location)
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit)
                player.swing(InteractionHand.MAIN_HAND)
                if (!player.isCreative) {
                    blockCount--
                }
            }
            placeCooldown = 1
        } finally {
            if (slot != player.inventory.selected) {
                Inventory.swapBack()
            }

            ANServiceRegistry.runtime.rotationManager.resetSilentRotation()

        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        val positions = renderPositions
        if (positions.isEmpty()) return
        val line = lineColor.value.toANColor()
        val side = sideColor.value.toANColor()
        for (pos in positions) {
            ANRender3DEngine.box(context, AABB(pos), line, side)
        }
    }

    private fun bestAdjacentPlacePos(pos: BlockPos): BlockPos? {
        val player = mc.player ?: return null
        return Direction.entries
            .asSequence()
            .filter { it != Direction.UP }
            .map { pos.relative(it) }
            .filter { canPlaceWithSupport(it) }
            .minByOrNull { player.distanceToSqr(Vec3.atCenterOf(it)) }
    }

    private fun createPlacementPlans(centerPos: BlockPos, side: Direction, slot: Int, slabMode: Boolean): List<PlacementPlan> {
        val plans = ArrayList<PlacementPlan>()
        val selectedBlock = selectedBlock(slot)
        val slabSide = if (slabMode) Direction.DOWN else (if (isSlabStack(slot)) Direction.UP else null)

        for ((index, offset) in widthOffsets().withIndex()) {
            val target = centerPos.relative(side, offset)
            if (!clientCanPlace(target)) continue
            if (index > 0 && selectedBlock != null && !isAdjacentToRoadBlock(target, selectedBlock, slabMode)) continue

            val targetSide = placeSide(target) ?: continue
            val hit = hitResult(target, targetSide, slabSide) ?: continue
            plans.add(PlacementPlan(target, hit))
        }

        return plans
    }

    private fun widthOffsets(): List<Int> {
        val offsets = ArrayList<Int>()
        offsets.add(0)
        var offset = 1
        while (offsets.size < width.value) {
            offsets.add(offset)
            if (offsets.size < width.value) {
                offsets.add(-offset)
            }
            offset++
        }
        return offsets
    }

    private fun isAdjacentToRoadBlock(pos: BlockPos, selectedBlock: Block, slabMode: Boolean): Boolean {
        val level = mc.level ?: return false
        for (direction in Direction.Plane.HORIZONTAL) {
            val neighborState = level.getBlockState(pos.relative(direction))
            val neighborBlock = neighborState.block
            if (slabMode) {
                if (neighborBlock is SlabBlock) return true
            } else if (neighborBlock == selectedBlock) {
                return true
            }
        }
        return false
    }

    private fun hitResult(pos: BlockPos, side: Direction, slabSide: Direction?): BlockHitResult? {
        val neighbor = pos.relative(side.opposite)
        val location = when (slabSide) {
            Direction.UP -> Vec3(pos.x + 0.5, pos.y + 0.01, pos.z + 0.5)
            Direction.DOWN -> Vec3(pos.x + 0.5, pos.y + 0.99, pos.z + 0.5)
            else -> Vec3.atCenterOf(neighbor)
        }
        return BlockHitResult(location, side, neighbor, false)
    }

    private fun placeSide(pos: BlockPos): Direction? {
        val level = mc.level ?: return null
        val below = pos.below()
        if (level.getBlockState(below).canClick()) return Direction.UP

        for (direction in Direction.entries) {
            val neighbor = pos.relative(direction.opposite)
            if (level.getBlockState(neighbor).canClick()) return direction
        }
        return null
    }

    private fun clientCanPlace(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        if (!level.isInWorldBounds(pos)) return false
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced()
    }

    private fun canPlaceWithSupport(pos: BlockPos): Boolean =
        clientCanPlace(pos) && placeSide(pos) != null

    private fun isSlab(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        return level.getBlockState(pos).block is SlabBlock
    }

    private fun findBlockSlot(allowSlab: Boolean): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        val selected = player.inventory.selected
        if (selected in 0 until Inventory.HOTBAR_SIZE && isValidBlockStack(selected, allowSlab)) return selected

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (isValidBlockStack(slot, allowSlab)) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun findSlabSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        val selected = player.inventory.selected
        if (selected in 0 until Inventory.HOTBAR_SIZE && isSlabStack(selected)) return selected

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (isSlabStack(slot)) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun isValidBlockStack(slot: Int, allowSlab: Boolean): Boolean {
        val player = mc.player ?: return false
        val item = player.inventory.getItem(slot).item as? BlockItem ?: return false
        if (item == Items.COBWEB) return false
        return allowSlab || item.block !is SlabBlock
    }

    private fun isSlabStack(slot: Int): Boolean {
        val player = mc.player ?: return false
        val item = player.inventory.getItem(slot).item as? BlockItem ?: return false
        if (item == Items.COBWEB) return false
        return item.block is SlabBlock
    }

    private fun selectedBlock(slot: Int): Block? {
        val player = mc.player ?: return null
        return (player.inventory.getItem(slot).item as? BlockItem)?.block
    }

    private fun rotateTo(vec: Vec3) {
        val player = mc.player ?: return
        val rotations = RotationUtil.getRotationsTo(player.eyePosition, vec)
        ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))
    }

    private fun clearRender() {
        renderPositions.clear()
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private fun BlockState.canClick(): Boolean {
        return !isAir && !canBeReplaced()
    }

    private data class PlacementPlan(val pos: BlockPos, val hit: BlockHitResult)
}
