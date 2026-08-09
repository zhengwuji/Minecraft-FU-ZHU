package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.inventory.SilentSwapType
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.ShulkerBoxMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs
import kotlin.math.ceil

class ANAutoFarm : ANBaseModule(
    name = "AutoFarm",
    description = "全自动在选定区域内按蛇形路径自动耕地、种植或收获",
    category = ANModuleCategory.MISC,
    chineseName = "自动农场"
), ANWorldRenderModule {
    val page = addSetting(ANSetting("Page", Page.MAIN))
    val mode = addSetting(ANSetting("Mode", FarmMode.Till) { isPage(Page.MAIN) })
    val crop = addSetting(ANSetting("Crop", Crop.Wheat) { isPage(Page.MAIN) && mode.value == FarmMode.Plant })
    val sizeX = addSetting(ANSetting("SizeX", 32, -128, 128) { isPage(Page.AREA) })
    val sizeZ = addSetting(ANSetting("SizeZ", 32, -128, 128) { isPage(Page.AREA) })
    val sizeY = addSetting(ANSetting("SizeY", 1, 1, 64) { isPage(Page.AREA) })
    val actionRange = addSetting(ANSetting("FarmRange", 4.5f, 1.0f, 6.0f) { isPage(Page.MAIN) })
    val actionsPerTick = addSetting(ANSetting("FarmPerTick", 2, 1, 8) { isPage(Page.MAIN) })
    val renderColor = addSetting(ANSetting("RenderColor", ColorGroupSetting(Color(0x66AAFF66, true).rgb)) { isPage(Page.RENDER) })
    val chestColor = addSetting(ANSetting("ChestColor", ColorGroupSetting(Color(0x66FFCC55, true).rgb)) { isPage(Page.RENDER) })

    private var origin = BlockPos.ZERO
    private var running = false
    private var goPoints = emptyList<BlockPos>()
    private var goPointIndex = 0
    private var currentPathTarget: BlockPos? = null
    private var lastPathMs = 0L
    private var lastActionMs = 0L
    private var lastMessageMs = 0L
    private val chests = ArrayList<ChestBinding>()
    private var storagePhase = StoragePhase.IDLE
    private var storageChestIndex = 0
    private var interactedChest = false

    enum class Page {
        MAIN,
        AREA,
        RENDER
    }

    enum class FarmMode {
        Till,
        Plant,
        Harvest
    }

    enum class Crop(val seed: Item, val block: net.minecraft.world.level.block.Block) {
        Wheat(Items.WHEAT_SEEDS, Blocks.WHEAT),
        Carrot(Items.CARROT, Blocks.CARROTS),
        Potato(Items.POTATO, Blocks.POTATOES),
        Beetroot(Items.BEETROOT_SEEDS, Blocks.BEETROOTS)
    }

    private enum class StoragePhase {
        IDLE,
        WALK_TO_CHEST,
        OPEN_CHEST,
        TRANSFER
    }

    override fun onEnable() {
        val player = mc.player
        origin = player?.blockPosition()?.below() ?: BlockPos.ZERO
        running = false
        goPoints = emptyList()
        goPointIndex = 0
        currentPathTarget = null
        storagePhase = StoragePhase.IDLE
        BaritoneHelper.configure()
        sendClientMessage("区域角点已设置，中键方块可重设角点，中键箱子可绑定存储箱")
    }

    override fun onDisable() {
        running = false
        goPoints = emptyList()
        goPointIndex = 0
        currentPathTarget = null
        storagePhase = StoragePhase.IDLE
        interactedChest = false
        Inventory.endSwap()
        Inventory.swapBack()
        BaritoneHelper.cancel()
        BaritoneHelper.restore()
    }

    override fun onUnload() {
        onDisable()
    }

    override fun onTick() {
        val player = mc.player ?: return
        if (mc.level == null || player.isSpectator || player.isCreative) return

        if (storagePhase != StoragePhase.IDLE) {
            tickStorage()
            return
        }

        if (!running) return

        if (mode.value == FarmMode.Harvest && isInventoryFull()) {
            startStorage()
            return
        }

        navigateSnakePath()
        runLocalActions()
        if (goPointIndex >= goPoints.size) {
            running = false
            BaritoneHelper.cancel()
            sendClientMessage("${mode.value}任务完成")
        }
    }

    override fun onMousePressed(button: Int) {
        if (!enabled || button != MIDDLE_MOUSE_BUTTON) return
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val pos = hit.blockPos
        val state = mc.level?.getBlockState(pos) ?: return

        if (isStorageBlock(state)) {
            bindChest(pos, state)
            return
        }

        origin = pos
        running = false
        BaritoneHelper.cancel()
        //sendClientMessage("农场区域角点已设置为 $origin")
    }

    override fun renderWorld(context: LevelRenderContext) {
        val bounds = regionBounds()
        val line = ANColor.fromArgb(renderColor.value.getColor()).withAlpha(255)
        ANRender3DEngine.box(context, bounds.toAabb(), line, null)

        val chestLine = ANColor.fromArgb(chestColor.value.getColor()).withAlpha(255)
        for (binding in chests) {
            ANRender3DEngine.box(context, AABB(binding.pos), chestLine, null)
            binding.secondary?.let { ANRender3DEngine.box(context, AABB(it), chestLine, null) }
        }
    }

    @ANEventHandler
    fun onPacketSend(event: PacketEvent.Send) {
        if (!enabled || running) return
        val packet = event.packet
        when (packet) {
            is ServerboundUseItemOnPacket -> {
                val pos = packet.hitResult.blockPos
                if (!regionBounds().contains(pos)) return
                if (mode.value == FarmMode.Till || mode.value == FarmMode.Plant) startTask()
            }
            is ServerboundPlayerActionPacket -> {
                if (packet.action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return
                if (!regionBounds().contains(packet.pos)) return
                if (mode.value == FarmMode.Harvest) startTask()
            }
        }
    }

    private fun startTask() {
        goPoints = generateSnakePoints(regionBounds(), pathBandWidth())
        goPointIndex = 0
        currentPathTarget = null
        lastPathMs = 0L
        lastActionMs = 0L
        lastMessageMs = 0L
        running = goPoints.isNotEmpty()
        if (running) {
            sendClientMessage("${mode.value}任务开始")
        } else {
            sendClientMessage("区域无有效巡航点")
        }
    }

    private fun navigateSnakePath() {
        val target = goPoints.getOrNull(goPointIndex) ?: return
        if (isWaypointReached(target)) {
            advanceWaypoint()
            return
        }

        val now = System.currentTimeMillis()
        if (currentPathTarget != target || now - lastPathMs > PATH_REFRESH_MS) {
            BaritoneHelper.pathNear(target, WAYPOINT_REACH_BLOCKS)
            currentPathTarget = target
            lastPathMs = now
        }
    }

    private fun advanceWaypoint() {
        goPointIndex++
        currentPathTarget = null
        BaritoneHelper.cancel()
    }

    private fun isWaypointReached(target: BlockPos): Boolean {
        val playerPos = mc.player?.blockPosition() ?: return false
        val dx = playerPos.x - target.x
        val dz = playerPos.z - target.z
        val targetStandY = target.y + 1
        return dx * dx + dz * dz <= WAYPOINT_REACH_BLOCKS * WAYPOINT_REACH_BLOCKS &&
            abs(playerPos.y - targetStandY) <= WAYPOINT_REACH_Y_TOLERANCE
    }

    private fun runLocalActions() {
        val now = System.currentTimeMillis()
        if (now - lastActionMs < ACTION_INTERVAL_MS) return
        lastActionMs = now

        var actions = 0
        for (pos in localCandidatePositions()) {
            if (actions >= actionsPerTick.value) break
            val handled = when (mode.value) {
                FarmMode.Till -> till(pos)
                FarmMode.Plant -> plant(pos)
                FarmMode.Harvest -> harvest(pos)
            }
            if (handled) actions++
        }
    }

    private fun till(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        if (!canTill(level, pos)) return false
        val hoeSlot = findHoeSlot()
        if (hoeSlot == Inventory.INVALID_SLOT) {
            sendMissingToolMessage("没有可用锄头")
            running = false
            BaritoneHelper.cancel()
            return false
        }
        return useSlot(hoeSlot) {
            useOn(pos, Direction.UP, Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5))
        }
    }

    private fun plant(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        if (!canPlant(level, pos)) return false
        val seedSlot = findSeedSlot(crop.value.seed)
        if (seedSlot == Inventory.INVALID_SLOT) {
            sendMissingToolMessage("没有${crop.value}种子")
            running = false
            BaritoneHelper.cancel()
            return false
        }
        return useSlot(seedSlot) {
            useOn(pos, Direction.UP, Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5))
        }
    }

    private fun harvest(pos: BlockPos): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val state = level.getBlockState(pos)
        val cropBlock = state.block as? CropBlock ?: return false
        if (!cropBlock.isMaxAge(state)) return false

        val rotations = RotationUtil.getRotationsTo(player.eyePosition, Vec3.atCenterOf(pos))
        ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))
        val result = mc.gameMode?.destroyBlock(pos) == true
        if (result) player.swing(InteractionHand.MAIN_HAND)
        return result
    }

    private fun useOn(pos: BlockPos, direction: Direction, hitVec: Vec3) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val rotations = RotationUtil.getRotationsTo(player.eyePosition, hitVec)
        ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))
        val hit = BlockHitResult(hitVec, direction, pos, false)
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        player.swing(InteractionHand.MAIN_HAND)
    }

    private fun useSlot(slot: Int, action: () -> Unit): Boolean {
        val swapType = if (slot in 0 until Inventory.HOTBAR_SIZE) SilentSwapType.HOTBAR else SilentSwapType.INVENTORY
        if (!Inventory.startSwap(slot, swapType)) return false
        return try {
            action()
            true
        } finally {
            Inventory.endSwap(swapType)
            ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
        }
    }

    private fun localCandidatePositions(): List<BlockPos> {
        val player = mc.player ?: return emptyList()
        val bounds = regionBounds()
        val center = player.blockPosition()
        val radius = ceil(actionRange.value.toDouble()).toInt()
        val radiusSq = actionRange.value * actionRange.value
        val positions = ArrayList<BlockPos>()

        for (x in center.x - radius..center.x + radius) {
            for (y in bounds.minY..bounds.maxY) {
                if (kotlin.math.abs(y - center.y) > radius) continue
                for (z in center.z - radius..center.z + radius) {
                    val pos = BlockPos(x, y, z)
                    if (!bounds.contains(pos)) continue
                    if (pos.distToCenterSqr(player.eyePosition) > radiusSq) continue
                    positions += pos
                }
            }
        }

        return positions.sortedBy { it.distToCenterSqr(player.eyePosition) }
    }

    private fun canTill(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        val above = level.getBlockState(pos.above())
        if (!above.isAir && !above.canBeReplaced()) return false
        return state.`is`(Blocks.DIRT) ||
            state.`is`(Blocks.GRASS_BLOCK) ||
            state.`is`(Blocks.DIRT_PATH) ||
            state.`is`(Blocks.COARSE_DIRT) ||
            state.`is`(Blocks.PODZOL) ||
            state.`is`(Blocks.ROOTED_DIRT)
    }

    private fun canPlant(level: Level, pos: BlockPos): Boolean {
        if (!level.getBlockState(pos).`is`(Blocks.FARMLAND)) return false
        val above = level.getBlockState(pos.above())
        return above.isAir || above.canBeReplaced()
    }

    private fun findHoeSlot(): Int {
        val result = Inventory.findBest(
            0 until Inventory.MAIN_SIZE,
            { stack -> stack.`is`(ItemTags.HOES) },
            { stack -> Inventory.materialRank(stack).toFloat() }
        )
        return if (result.found) result.slot else Inventory.INVALID_SLOT
    }

    private fun findSeedSlot(seed: Item): Int {
        val result = Inventory.find(0 until Inventory.MAIN_SIZE) { stack -> stack.`is`(seed) }
        return if (result.found) result.slot else Inventory.INVALID_SLOT
    }

    private fun startStorage() {
        if (chests.isEmpty()) {
            sendClientMessage("背包已满，但未绑定存储箱")
            running = false
            BaritoneHelper.cancel()
            return
        }
        storagePhase = StoragePhase.WALK_TO_CHEST
        storageChestIndex = nearestChestIndex()
        interactedChest = false
        currentPathTarget = null
        sendClientMessage("背包已满，前往绑定箱子存储农作物")
    }

    private fun tickStorage() {
        val player = mc.player ?: return
        val chest = chests.getOrNull(storageChestIndex) ?: run {
            storagePhase = StoragePhase.IDLE
            return
        }

        when (storagePhase) {
            StoragePhase.IDLE -> Unit
            StoragePhase.WALK_TO_CHEST -> {
                val distance = player.eyePosition.distanceTo(Vec3.atCenterOf(chest.pos))
                if (distance <= 3.0) {
                    BaritoneHelper.cancel()
                    storagePhase = StoragePhase.OPEN_CHEST
                    interactedChest = false
                } else {
                    BaritoneHelper.pathNear(chest.pos, 1)
                }
            }
            StoragePhase.OPEN_CHEST -> {
                if (currentStorageMenu() != null) {
                    storagePhase = StoragePhase.TRANSFER
                    return
                }
                if (!interactedChest) {
                    openChest(chest.pos)
                    interactedChest = true
                }
            }
            StoragePhase.TRANSFER -> {
                val menu = currentStorageMenu() ?: run {
                    storagePhase = StoragePhase.OPEN_CHEST
                    interactedChest = false
                    return
                }
                if (!transferHarvestItems(menu)) {
                    player.closeContainer()
                    storagePhase = StoragePhase.IDLE
                    sendClientMessage("CHECK")
                }
            }
        }
    }

    private fun transferHarvestItems(menu: AbstractContainerMenu): Boolean {
        val player = mc.player ?: return false
        val storageSize = (menu.slots.size - PLAYER_INVENTORY_MENU_SLOTS).coerceAtLeast(0)
        for (slot in storageSize until menu.slots.size) {
            val stack = menu.slots[slot].item
            if (stack.isEmpty || !isHarvestOutput(stack)) continue
            mc.gameMode?.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player)
            return true
        }
        return false
    }

    private fun currentStorageMenu(): AbstractContainerMenu? {
        val menu = mc.player?.containerMenu ?: return null
        return if (menu is ChestMenu || menu is ShulkerBoxMenu) menu else null
    }

    private fun openChest(pos: BlockPos) {
        useOn(pos, Direction.UP, Vec3.atCenterOf(pos))
    }

    private fun bindChest(pos: BlockPos, state: BlockState) {
        val secondary = if (state.block is ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
            val type = state.getValue(ChestBlock.TYPE)
            val facing = state.getValue(ChestBlock.FACING)
            when (type) {
                ChestType.RIGHT -> pos.relative(facing.counterClockWise)
                ChestType.LEFT -> pos.relative(facing.clockWise)
                ChestType.SINGLE -> null
            }
        } else {
            null
        }

        if (chests.any { it.pos == pos || it.secondary == pos || (secondary != null && (it.pos == secondary || it.secondary == secondary)) }) {
            //sendClientMessage("该箱子已绑定")
            return
        }
        chests += ChestBinding(pos, secondary)
        //sendClientMessage("已绑定存储箱：$pos")
    }

    private fun nearestChestIndex(): Int {
        val player = mc.player ?: return 0
        return chests.indices.minByOrNull { index -> player.distanceToSqr(Vec3.atCenterOf(chests[index].pos)) } ?: 0
    }

    private fun isInventoryFull(): Boolean {
        val player = mc.player ?: return false
        for (slot in 0 until Inventory.MAIN_SIZE) {
            if (player.inventory.getItem(slot).isEmpty) return false
        }
        return true
    }

    private fun isHarvestOutput(stack: ItemStack): Boolean {
        val item = stack.item
        if (stack.`is`(ItemTags.HOES)) return false
        if (item == crop.value.seed) return false
        return item == Items.WHEAT ||
            item == Items.CARROT ||
            item == Items.POTATO ||
            item == Items.BEETROOT ||
            item == Items.WHEAT_SEEDS ||
            item == Items.BEETROOT_SEEDS
    }

    private fun isStorageBlock(state: BlockState): Boolean =
        state.block is ChestBlock || state.block is ShulkerBoxBlock

    private fun generateSnakePoints(bounds: FarmBounds, bandWidth: Int): List<BlockPos> {
        val points = ArrayList<BlockPos>()
        var leftToRight = true
        for (z in bounds.minZ..bounds.maxZ step bandWidth.coerceAtLeast(1)) {
            if (leftToRight) {
                points += BlockPos(bounds.minX, origin.y, z)
                points += BlockPos(bounds.maxX, origin.y, z)
            } else {
                points += BlockPos(bounds.maxX, origin.y, z)
                points += BlockPos(bounds.minX, origin.y, z)
            }
            leftToRight = !leftToRight
        }
        return points
    }

    private fun pathBandWidth(): Int =
        ceil(actionRange.value * PATH_BAND_RANGE_MULTIPLIER).toInt().coerceAtLeast(1)

    private fun regionBounds(): FarmBounds {
        val otherX = origin.x + sizeX.value
        val otherZ = origin.z + sizeZ.value
        return FarmBounds(
            minX = minOf(origin.x, otherX),
            maxX = maxOf(origin.x, otherX),
            minY = origin.y,
            maxY = origin.y + sizeY.value - 1,
            minZ = minOf(origin.z, otherZ),
            maxZ = maxOf(origin.z, otherZ)
        )
    }

    private fun isPage(target: Page): Boolean = page.value == target

    private fun sendMissingToolMessage(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastMessageMs >= MESSAGE_INTERVAL_MS) {
            sendClientMessage(message)
            lastMessageMs = now
        }
    }

    private data class FarmBounds(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int
    ) {
        fun contains(pos: BlockPos): Boolean =
            pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ

        fun toAabb(): AABB =
            AABB(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX + 1.0, maxY + 1.0, maxZ + 1.0)
    }

    private data class ChestBinding(val pos: BlockPos, val secondary: BlockPos?)

    private companion object {
        private const val MIDDLE_MOUSE_BUTTON = 2
        private const val PATH_REFRESH_MS = 1200L
        private const val ACTION_INTERVAL_MS = 120L
        private const val MESSAGE_INTERVAL_MS = 1500L
        private const val PLAYER_INVENTORY_MENU_SLOTS = 36
        private const val PATH_BAND_RANGE_MULTIPLIER = 0.8f
        private const val WAYPOINT_REACH_BLOCKS = 2
        private const val WAYPOINT_REACH_Y_TOLERANCE = 2
    }
}
