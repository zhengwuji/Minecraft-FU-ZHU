package anpilot.client.features.ai.task.elytrapilot

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.manager.inventory.Inventory as ANInventory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

internal object ElytraStorageSupport {
    const val SHULKER_SIZE = 27
    const val SHULKER_SEARCH_RADIUS = 5.0
    const val VERIFY_TIMEOUT = 60
    const val PICKUP_TIMEOUT = 120
    const val MAX_ROUNDS = 4

    fun disablePilot(agent: ANAgent) {
        ANAgent.minecraft.player?.closeContainer()
        BaritoneHelper.cancel()
        agent.rotation.resume()
        agent.module.disable()
    }

    fun placeInventoryBlock(agent: ANAgent, inventorySlot: Int, pos: BlockPos): Boolean {
        ANAgent.minecraft.player?.closeContainer()
        if (!switchToHotbar(inventorySlot)) return false
        val player = ANAgent.minecraft.player ?: return false
        val hit = supportHitResult(pos) ?: return false
        lookAt(agent, Vec3.atCenterOf(pos))
        ANAgent.minecraft.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        player.swing(InteractionHand.MAIN_HAND)
        return true
    }

    fun switchToHotbar(inventorySlot: Int): Boolean {
        val player = ANAgent.minecraft.player ?: return false
        val hotbarSlot = when (inventorySlot) {
            in 0 until ANInventory.HOTBAR_SIZE -> inventorySlot
            else -> {
                val selected = player.inventory.selected
                if (player.containerMenu !is InventoryMenu) player.closeContainer()
                if (!ANInventory.swapInventorySlot(inventorySlot, selected)) return false
                selected
            }
        }
        return ANInventory.switchTo(hotbarSlot)
    }

    fun interactBlock(agent: ANAgent, pos: BlockPos) {
        val player = ANAgent.minecraft.player ?: return
        lookAt(agent, Vec3.atCenterOf(pos))
        ANAgent.minecraft.gameMode?.useItemOn(
            player,
            InteractionHand.MAIN_HAND,
            BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
        )
        player.swing(InteractionHand.MAIN_HAND)
    }

    private var miningPos: BlockPos? = null

    fun mineBlock(agent: ANAgent, pos: BlockPos) {
        val player = ANAgent.minecraft.player ?: return
        val level = ANAgent.minecraft.level ?: return
        if (miningPos != null && level.getBlockState(miningPos!!).isAir) {
            miningPos = null
        }
        lookAt(agent, Vec3.atCenterOf(pos))
        val gameMode = ANAgent.minecraft.gameMode ?: return
        if (miningPos != pos) {
            gameMode.startDestroyBlock(pos, Direction.UP)
            miningPos = pos
        } else {
            gameMode.continueDestroyBlock(pos, Direction.UP)
        }
        player.swing(InteractionHand.MAIN_HAND)
    }

    fun mineBlock(pos: BlockPos) {
        val player = ANAgent.minecraft.player ?: return
        val level = ANAgent.minecraft.level ?: return
        if (miningPos != null && level.getBlockState(miningPos!!).isAir) {
            miningPos = null
        }
        val gameMode = ANAgent.minecraft.gameMode ?: return
        if (miningPos != pos) {
            gameMode.startDestroyBlock(pos, Direction.UP)
            miningPos = pos
        } else {
            gameMode.continueDestroyBlock(pos, Direction.UP)
        }
        player.swing(InteractionHand.MAIN_HAND)
    }

    fun findLootedElytraFrame(): ItemFrame? {
        val player = ANAgent.minecraft.player ?: return null
        val level = ANAgent.minecraft.level ?: return null
        val pos = player.blockPosition()
        val bounding = player.boundingBox.inflate(SHULKER_SEARCH_RADIUS)
        return level.getEntitiesOfClass(ItemFrame::class.java, bounding) { frame ->
            frame.getItem().isEmpty
        }.minByOrNull { it.distanceToSqr(player) }
    }

    fun resetMining() {
        miningPos = null
    }

    fun findNearbyShulker(): Shulker? {
        val player = ANAgent.minecraft.player ?: return null
        val level = ANAgent.minecraft.level ?: return null
        val bounding = player.boundingBox.inflate(SHULKER_SEARCH_RADIUS)
        return level.getEntitiesOfClass(Shulker::class.java, bounding) {
            it.isAlive
        }.minByOrNull { it.distanceToSqr(player) }
    }

    fun findNearestShulker(pos: BlockPos): Shulker? {
        return findNearbyShulker()
    }

    fun findNearbyShulkerBoxPos(): BlockPos? {
        val player = ANAgent.minecraft.player ?: return null
        val level = ANAgent.minecraft.level ?: return null
        val center = player.blockPosition()
        val list = mutableListOf<BlockPos>()
        for (y in -3..3) {
            for (x in -4..4) {
                for (z in -4..4) {
                    val pos = center.offset(x, y, z)
                    if (isShulkerBlock(pos)) list.add(pos)
                }
            }
        }
        return list.minByOrNull { it.distSqr(center) }
    }

    fun findEndShipChestPositions(elytraPos: BlockPos): List<BlockPos> {
        val level = ANAgent.minecraft.level ?: return emptyList()
        val list = mutableListOf<BlockPos>()
        for (y in -2..2) {
            for (x in -4..4) {
                for (z in -4..4) {
                    val pos = elytraPos.offset(x, y, z)
                    val state = level.getBlockState(pos)
                    if (state.`is`(Blocks.CHEST)) {
                        list.add(pos)
                    }
                }
            }
        }
        return list.sortedBy { it.distSqr(elytraPos) }
    }

    internal fun findChestFrontPositions(elytraPos: BlockPos): List<BlockPos> {
        val chests = findEndShipChestPositions(elytraPos)
        val level = ANAgent.minecraft.level ?: return emptyList()
        val fronts = mutableListOf<BlockPos>()
        for (chestPos in chests) {
            val state = level.getBlockState(chestPos)
            val facing = if (state.hasProperty(ChestBlock.FACING)) state.getValue(ChestBlock.FACING) else Direction.NORTH
            val frontPos = chestPos.relative(facing)
            if (isWalkable(frontPos)) {
                fronts.add(frontPos)
            }
        }
        return fronts.distinct().sortedBy { it.distSqr(elytraPos) }
    }

    fun findNearbyPlacePos(center: BlockPos): BlockPos? {
        val level = ANAgent.minecraft.level ?: return null
        val list = mutableListOf<BlockPos>()
        for (y in -1..1) {
            for (x in -2..2) {
                for (z in -2..2) {
                    val pos = center.offset(x, y, z)
                    if (isPlaceablePosition(level, pos)) list.add(pos)
                }
            }
        }
        return list.minByOrNull { it.distSqr(center) }
    }

    private fun isPlaceablePosition(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
        if (!level.getBlockState(pos).isAir) return false
        val belowState = level.getBlockState(pos.below())
        return belowState.isSolid
    }

    fun supportHitResult(pos: BlockPos): BlockHitResult? {
        val below = pos.below()
        val level = ANAgent.minecraft.level ?: return null
        if (level.getBlockState(below).isSolid) {
            return BlockHitResult(Vec3.atCenterOf(below), Direction.UP, below, false)
        }
        for (dir in Direction.values()) {
            if (dir == Direction.UP) continue
            val neighbor = pos.relative(dir)
            if (level.getBlockState(neighbor).isSolid) {
                return BlockHitResult(Vec3.atCenterOf(neighbor), dir.opposite, neighbor, false)
            }
        }
        return null
    }

    fun isWalkable(pos: BlockPos): Boolean {
        val level = ANAgent.minecraft.level ?: return false
        return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty &&
                level.getBlockState(pos).getCollisionShape(level, pos).isEmpty &&
                level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty
    }

    fun clickQuickMove(slot: Int): Boolean {
        val player = ANAgent.minecraft.player ?: return false
        val gameMode = ANAgent.minecraft.gameMode ?: return false
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot, 0, ClickType.QUICK_MOVE, player)
        return true
    }

    fun findPlayerMenuSlot(menu: AbstractContainerMenu, firstPlayerSlot: Int, predicate: (ItemStack) -> Boolean): Int {
        for (slot in firstPlayerSlot until menu.slots.size) {
            val stack = menu.slots[slot].item
            if (!stack.isEmpty && predicate(stack)) return slot
        }
        return -1
    }

    fun containerEmptySlots(menu: AbstractContainerMenu, containerSize: Int): Int {
        var count = 0
        for (slot in 0 until containerSize) {
            if (menu.slots[slot].item.isEmpty) count++
        }
        return count
    }

    fun countContainerItems(menu: AbstractContainerMenu, containerSize: Int, predicate: (ItemStack) -> Boolean): Int {
        var count = 0
        for (slot in 0 until containerSize) {
            val stack = menu.slots[slot].item
            if (!stack.isEmpty && predicate(stack)) count += stack.count
        }
        return count
    }

    fun countInventoryElytra(): Int {
        var count = 0
        forInventoryStack { stack ->
            if (stack.`is`(Items.ELYTRA)) count += stack.count
        }
        return count
    }

    fun emptyInventorySlots(): Int {
        var count = 0
        forInventoryStack { stack ->
            if (stack.isEmpty) count++
        }
        return count
    }

    fun findUsableShulkerSlot(): Int = findInventorySlot { isShulkerBox(it) && shulkerFreeSlots(it) > 0 && !shulkerHasFireworks(it) }

    fun findFullShulkerSlot(): Int = findInventorySlot(::isFullShulkerBox)

    fun shulkerHasFireworks(stack: ItemStack): Boolean {
        if (!isShulkerBox(stack)) return false
        val tag = stack.tag ?: return false
        val blockEntityTag = tag.getCompound("BlockEntityTag")
        if (!blockEntityTag.contains("Items", 9)) return false
        val items = blockEntityTag.getList("Items", 10)
        for (i in 0 until items.size) {
            val itemCompound = items.getCompound(i)
            val id = itemCompound.getString("id")
            if (id == "minecraft:firework_rocket") return true
        }
        return false
    }

    fun findFireworksShulkerSlot(): Int = findInventorySlot { shulkerHasFireworks(it) }

    fun findEnderChestSlot(): Int = findInventorySlot { it.`is`(Items.ENDER_CHEST) }

    fun findSilkTouchPickaxeSlot(): Int =
        findInventorySlot { it.`is`(ItemTags.PICKAXES) && ANInventory.hasEnchantment(it, Enchantments.SILK_TOUCH) }

    fun findPickaxeSlot(): Int =
        findInventorySlot { it.`is`(ItemTags.PICKAXES) }

    fun countShulkerBoxes(): Int = countInventoryItems(::isShulkerBox)

    fun countEnderChests(): Int = countInventoryItems { it.`is`(Items.ENDER_CHEST) }

    fun isShulkerBlock(pos: BlockPos): Boolean =
        ANAgent.minecraft.level?.getBlockState(pos)?.block is ShulkerBoxBlock

    fun isEnderChestBlock(pos: BlockPos): Boolean =
        ANAgent.minecraft.level?.getBlockState(pos)?.`is`(Blocks.ENDER_CHEST) == true

    fun isShulkerBox(stack: ItemStack): Boolean {
        val item = stack.item as? BlockItem ?: return false
        return item.block is ShulkerBoxBlock
    }

    fun isFullShulkerBox(stack: ItemStack): Boolean =
        isShulkerBox(stack) && shulkerFreeSlots(stack) <= 0

    fun shulkerFreeSlots(stack: ItemStack): Int =
        SHULKER_SIZE - shulkerUsedSlots(stack)

    private fun shulkerUsedSlots(stack: ItemStack): Int {
        val tag = stack.tag ?: return 0
        val blockEntityTag = tag.getCompound("BlockEntityTag")
        if (!blockEntityTag.contains("Items", 9)) return 0
        return blockEntityTag.getList("Items", 10).size
    }

    fun lookAt(agent: ANAgent, pos: Vec3) {
        val player = ANAgent.minecraft.player ?: return
        val dx = pos.x - player.x
        val dz = pos.z - player.z
        val dy = pos.y - player.eyeY
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontal))).toFloat()
        agent.rotation.request(yaw, pitch)
    }

    private fun findInventorySlot(predicate: (ItemStack) -> Boolean): Int {
        val player = ANAgent.minecraft.player ?: return ANInventory.INVALID_SLOT
        for (slot in 0 until ANInventory.MAIN_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && predicate(stack)) return slot
        }
        return ANInventory.INVALID_SLOT
    }

    private fun countInventoryItems(predicate: (ItemStack) -> Boolean): Int {
        var count = 0
        forInventoryStack { stack ->
            if (!stack.isEmpty && predicate(stack)) count += stack.count
        }
        return count
    }

    private inline fun forInventoryStack(action: (ItemStack) -> Unit) {
        val player = ANAgent.minecraft.player ?: return
        for (slot in 0 until ANInventory.MAIN_SIZE) {
            action(player.inventory.getItem(slot))
        }
    }
}
