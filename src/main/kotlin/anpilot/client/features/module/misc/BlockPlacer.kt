package anpilot.client.features.module.misc

import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.inventory.SilentSwapType
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.FrontAndTop
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.floor

class BlockPlacer(private val context: Context) {

    interface Context {
        val placeRangeSqr: Float
        val blocksPerTick: Int
        val placeDelay: Int
        val inventorySwap: Boolean
        val onlyBelowFeet: Boolean
        val ignoreRedstoneOrientation: Boolean
        val placeBlocks: List<PlaceBlock>
        val schematicBlockMap: Map<BlockPos, BlockState>

        fun shouldSkipBlock(state: BlockState): Boolean
        fun onBlockPlaced(pos: BlockPos)
    }

    private val mc = Minecraft.getInstance()

    var placeCooldown = 0
        private set

    fun resetCooldown() {
        placeCooldown = 0
    }

    fun tick() {
        val player = mc.player ?: return
        if (mc.level == null || player.isSpectator) return
        if (placeCooldown > 0) {
            placeCooldown--
            return
        }

        var placed = 0
        for (block in context.placeBlocks) {
            if (placed >= context.blocksPerTick) break
            val plan = findPlacementPlan(block, block.state.block.asItem().defaultInstance) ?: continue
            if (placeBlock(block, plan)) {
                context.onBlockPlaced(block.pos)
                placed++
            }
        }

        if (placed > 0) {
            placeCooldown = context.placeDelay
            ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
        }
    }

    fun isBlockBuilt(actual: BlockState, expected: BlockState): Boolean {
        return context.shouldSkipBlock(expected) || isCompatibleState(actual, expected, isPrediction = false)
    }

    fun isCompatibleState(actual: BlockState, expected: BlockState, isPrediction: Boolean = false): Boolean {
        if (actual.block != expected.block) return false
        for (property in expected.properties) {
            if (property.name !in PLACEMENT_PROPERTY_NAMES || !actual.hasProperty(property)) continue
            if (shouldIgnoreRedstoneOrientationProperty(expected, property.name)) continue

            if (property.name == "type") {
                val expectedVal = expected.getValue(property).toString().lowercase()
                val actualVal = actual.getValue(property).toString().lowercase()
                
                if (isPrediction && expectedVal == "double" && (actualVal == "top" || actualVal == "bottom")) continue
                
                if ((expectedVal == "left" || expectedVal == "right") &&
                    (actualVal == "single" || actualVal == "left" || actualVal == "right")
                ) continue
            }

            if (!samePropertyValue(actual, expected, property)) return false
        }
        return true
    }

    private fun shouldIgnoreRedstoneOrientationProperty(expected: BlockState, propertyName: String): Boolean {
        return context.ignoreRedstoneOrientation &&
                propertyName in REDSTONE_ORIENTATION_PROPERTY_NAMES &&
                isRedstoneOrientationBlock(expected)
    }

    fun findPlacementPlan(block: PlaceBlock, stack: ItemStack, ignoreFeetCheck: Boolean = false): PlacementPlan? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        if (context.shouldSkipBlock(block.state)) return null
        if (context.onlyBelowFeet && !ignoreFeetCheck) {
            val playerFeetY = floor(player.y - 0.001).toInt() + 1
            if (block.pos.y >= playerFeetY) return null
        }

        val posBelow = block.pos.below()
        val expectedBelow = context.schematicBlockMap[posBelow]
        if (expectedBelow != null) {
            val actualBelow = level.getBlockState(posBelow)
            if (!isBlockBuilt(actualBelow, expectedBelow)) return null
        }

        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(block.pos)) > context.placeRangeSqr) return null
        if (!level.isInWorldBounds(block.pos)) return null

        val actual = level.getBlockState(block.pos)
        if (isBlockBuilt(actual, block.state)) return null
        val isSlabPlacement = actual.block == block.state.block &&
                block.state.properties.any { it.name == "type" && block.state.getValue(it).toString() == "double" }
        if (!isSlabPlacement && !actual.isAir && !actual.canBeReplaced()) return null
        if (hasEntityBlocking(AABB(block.pos))) return null
        if (findBlockSlot(block.state.block.asItem()) == Inventory.INVALID_SLOT) return null

        val useScaffoldLogic = !context.schematicBlockMap.containsKey(posBelow)
        val directions = supportDirections(block.pos, useScaffoldLogic)
        if (directions.isEmpty()) return null

        for (direction in directions) {
            for (hit in placementHits(block.pos, direction, block.state)) {
                for (rotation in placementRotations(block, hit)) {
                    val predicted = simulatePlacementState(block, stack, hit, rotation) ?: continue
                    if (isCompatibleState(predicted, block.state, isPrediction = true)) {
                        return PlacementPlan(hit, rotation)
                    }
                }
            }
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun placeBlock(block: PlaceBlock, initialPlan: PlacementPlan, ignoreFeetCheck: Boolean = false): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false

        val slot = findBlockSlot(block.state.block.asItem())
        if (slot == Inventory.INVALID_SLOT) return false

        val connection = mc.connection ?: return false
        val swapType = if (slot in 0 until Inventory.HOTBAR_SIZE) SilentSwapType.HOTBAR else SilentSwapType.INVENTORY
        val oldSelectedSlot = player.inventory.selected
        if (!Inventory.startSwap(slot, swapType)) return false
        val locallySwappedHotbar = swapType == SilentSwapType.HOTBAR && oldSelectedSlot != slot
        if (locallySwappedHotbar) {
            player.inventory.selected = slot
        }

        return try {
            val plan = findPlacementPlan(block, player.getItemInHand(InteractionHand.MAIN_HAND), ignoreFeetCheck)
                ?: return false
            ANServiceRegistry.runtime.rotationManager.setSilentRotation(plan.rotation)

            val clickedBlock = (mc.level ?: return false).getBlockState(plan.hit.blockPos).block
            val wasSneaking = player.isShiftKeyDown
            val shouldSneak = isInteractableBlock(clickedBlock) && !wasSneaking

            val oldYaw = player.yRot
            val oldPitch = player.xRot
            val oldHeadYaw = player.yHeadRot
            val oldBodyYaw = player.yBodyRot
            if (shouldSneak) {
                player.isShiftKeyDown = true
                player.input.shiftKeyDown = true
                connection.send(ServerboundPlayerInputPacket(player.input.leftImpulse, player.input.forwardImpulse, player.input.jumping, true))
            }

            try {
                player.yRot = plan.rotation.yaw
                player.xRot = plan.rotation.pitch
                player.yHeadRot = plan.rotation.yaw
                player.yBodyRot = plan.rotation.yaw
                connection.send(ServerboundMovePlayerPacket.Rot(plan.rotation.yaw, plan.rotation.pitch, player.onGround()))
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit)
                player.swing(InteractionHand.MAIN_HAND)
            } finally {
                player.yRot = oldYaw
                player.xRot = oldPitch
                player.yHeadRot = oldHeadYaw
                player.yBodyRot = oldBodyYaw
                connection.send(ServerboundMovePlayerPacket.Rot(oldYaw, oldPitch, player.onGround()))
            }

            if (shouldSneak) {
                player.isShiftKeyDown = false
                player.input.shiftKeyDown = false
                connection.send(ServerboundPlayerInputPacket(player.input.leftImpulse, player.input.forwardImpulse, player.input.jumping, false))
            }
            isBlockBuilt((mc.level ?: return false).getBlockState(block.pos), block.state)
        } finally {
            if (locallySwappedHotbar) {
                player.inventory.selected = oldSelectedSlot
            }
            Inventory.endSwap(swapType)
        }
    }

    private fun supportDirections(pos: BlockPos, useScaffoldLogic: Boolean): List<Direction> {
        val level = mc.level ?: return emptyList()

        val schematicState = context.schematicBlockMap[pos]
        var priorityDirection: Direction? = null
        if (schematicState != null && (schematicState.block is ChestBlock ||
                    BuiltInRegistries.BLOCK.getKey(schematicState.block).path.contains("chest"))
        ) {
            val typeProp = schematicState.properties.find { it.name == "type" }
            if (typeProp != null) {
                val typeVal = schematicState.getValue(typeProp).toString().lowercase()
                if (typeVal == "left" || typeVal == "right") {
                    for (dir in listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                        val nPos = pos.relative(dir)
                        val nState = context.schematicBlockMap[nPos]
                        if (nState != null && nState.block == schematicState.block) {
                            val nTypeProp = nState.properties.find { it.name == "type" }
                            if (nTypeProp != null) {
                                val nTypeVal = nState.getValue(nTypeProp).toString().lowercase()
                                if ((typeVal == "left" && nTypeVal == "right") ||
                                    (typeVal == "right" && nTypeVal == "left")
                                ) {
                                    priorityDirection = dir.opposite
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        var order = if (priorityDirection != null) {
            listOf(priorityDirection) + SUPPORT_DIRECTION_ORDER.filter { it != priorityDirection }
        } else {
            SUPPORT_DIRECTION_ORDER.toList()
        }

        if (schematicState != null) {
            val typeProp = schematicState.properties.find { it.name == "type" || it.name == "half" }
            if (typeProp != null) {
                val typeVal = schematicState.getValue(typeProp).toString().lowercase()
                if (typeVal == "top" || typeVal == "upper") {
                    order = order.filter { it != Direction.UP }
                } else if (typeVal == "bottom" || typeVal == "lower") {
                    order = order.filter { it != Direction.DOWN }
                }
            }

            if (schematicState.block == Blocks.HOPPER) {
                val facingProp = schematicState.properties.find { it.name == "facing" }
                if (facingProp != null) {
                    val facingName = schematicState.getValue(facingProp).toString()
                    val facingDir = Direction.byName(facingName)
                    if (facingDir != null) {
                        val opposite = facingDir.opposite
                        order = listOf(opposite, facingDir) + order.filter { it != opposite && it != facingDir }
                    }
                }
            } else {
                val facingProp = schematicState.properties.find { it.name == "facing" }
                if (facingProp != null) {
                    val facingName = schematicState.getValue(facingProp).toString()
                    val facingDir = Direction.byName(facingName)
                    if (facingDir != null) {
                        order = listOf(facingDir, facingDir.opposite) + order.filter { it != facingDir && it != facingDir.opposite }
                    }
                }
            }

            val axisProp = schematicState.properties.find { it.name == "axis" }
            if (axisProp != null) {
                val axisName = schematicState.getValue(axisProp).toString()
                val axisDirs = when (axisName) {
                    "x" -> listOf(Direction.EAST, Direction.WEST)
                    "y" -> listOf(Direction.UP, Direction.DOWN)
                    "z" -> listOf(Direction.NORTH, Direction.SOUTH)
                    else -> emptyList()
                }
                if (axisDirs.isNotEmpty()) {
                    order = axisDirs + order.filter { it !in axisDirs }
                }
            }
        }

        val directions = ArrayList<Direction>()
        for (direction in order) {
            val neighbor = pos.relative(direction.opposite)
            if (!level.isInWorldBounds(neighbor)) continue
            val neighborState = level.getBlockState(neighbor)
            val canClickNeighbor = if (useScaffoldLogic) {
                !neighborState.isAir && !neighborState.canBeReplaced()
            } else {
                neighborState.canClick()
            }
            if (canClickNeighbor) directions += direction
        }
        return directions
    }

    private fun placementHits(pos: BlockPos, direction: Direction, state: BlockState): List<BlockHitResult> {
        val neighbor = pos.relative(direction.opposite)
        val neighborCenter = Vec3.atCenterOf(neighbor)

        val hitX = neighborCenter.x + direction.stepX * 0.5
        val hitZ = neighborCenter.z + direction.stepZ * 0.5

        val typeProp = state.properties.find { it.name == "type" || it.name == "half" }
        if (typeProp != null) {
            val typeVal = state.getValue(typeProp).toString().lowercase()
            val yCandidates = ArrayList<Double>()
            if (typeVal == "top" || typeVal == "upper") {
                if (direction == Direction.DOWN) {
                    yCandidates += 1.0
                } else {
                    yCandidates += 0.75
                    yCandidates += 0.85
                }
            } else if (typeVal == "bottom" || typeVal == "lower") {
                if (direction == Direction.UP) {
                    yCandidates += 0.0
                } else {
                    yCandidates += 0.25
                    yCandidates += 0.15
                }
            } else {
                yCandidates += 0.25
                yCandidates += 0.75
            }
            return yCandidates.map { yOffset ->
                val hitY = pos.y + yOffset
                BlockHitResult(Vec3(hitX, hitY, hitZ), direction, neighbor, false)
            }
        }

        val hitY = when (direction) {
            Direction.UP -> neighbor.y + 1.0
            Direction.DOWN -> neighbor.y + 0.0
            else -> neighbor.y + 0.5
        }
        return listOf(BlockHitResult(Vec3(hitX, hitY, hitZ), direction, neighbor, false))
    }

    private fun placementRotations(block: PlaceBlock, hit: BlockHitResult): List<Rotation> {
        val state = block.state
        val player = mc.player ?: return emptyList()
        val hitRotations = RotationUtil.getRotationsTo(player.eyePosition, hit.location)
        val rotations = ArrayList<Rotation>()

        redstonePlacementRotations(state)?.let { return it.distinct() }

        val orientationProp = state.properties.find { it.name == "orientation" }
        if (orientationProp != null) {
            val orientation = state.getValue(orientationProp)
            if (orientation is FrontAndTop) {
                rotations += rotationForOrientation(orientation)
            }
        }

        val facingProp = state.properties.find { it.name == "facing" }
        if (facingProp != null) {
            val facing = state.getValue(facingProp)
            if (facing is Direction) {
                val blockName = BuiltInRegistries.BLOCK.getKey(state.block).path
                val requiredRotation = getRequiredRotationForBlockFacing(blockName, facing, hitRotations[1])
                if (requiredRotation != null) {
                    rotations += requiredRotation
                }
            }
        }

        rotations += Rotation(hitRotations[0], hitRotations[1])
        for (direction in HORIZONTAL_ROTATION_DIRECTIONS) {
            for (pitch in pitchCandidatesForState(state, hitRotations[1])) {
                rotations += Rotation(yawForDirection(direction), pitch)
            }
        }

        if (facingProp != null) {
            val facing = state.getValue(facingProp)
            if (facing is Direction) {
                rotations += rotationForDirection(facing)
                rotations += rotationForDirection(facing.opposite)
            }
        }
        return rotations.distinct()
    }

    private fun pitchCandidatesForState(state: BlockState, hitPitch: Float): List<Float> {
        val pitchCandidates = linkedSetOf(hitPitch.coerceIn(-89f, 89f), 0f)
        val facingProp = state.properties.find { it.name == "facing" }
        val has3DAttachment = state.properties.any { it.name == "face" || it.name == "attachment" || it.name == "orientation" }
        if (facingProp != null || has3DAttachment) {
            pitchCandidates += -89f
            pitchCandidates += 89f
        }
        return pitchCandidates.toList()
    }

    private fun getRequiredRotationForBlockFacing(blockName: String, targetFacing: Direction, hitPitch: Float): Rotation? {
        return when {
            blockName.contains("observer") -> {
                rotationForNearestLookingDirection(targetFacing)
            }
            blockName.contains("piston") ||
                blockName.contains("dispenser") ||
                blockName.contains("dropper") -> {
                rotationForNearestLookingDirection(targetFacing.opposite)
            }
            blockName.contains("repeater") || blockName.contains("comparator") -> {
                if (targetFacing.axis.isHorizontal) Rotation(yawForDirection(targetFacing.opposite), 0f) else null
            }
            else -> {
                if (targetFacing.axis.isHorizontal) {
                    Rotation(yawForDirection(targetFacing), hitPitch)
                } else null
            }
        }
    }

    private fun redstonePlacementRotations(state: BlockState): List<Rotation>? {
        val blockName = BuiltInRegistries.BLOCK.getKey(state.block).path
        val facingProp = state.properties.find { it.name == "facing" }
        val facing = facingProp?.let { state.getValue(it) } as? Direction

        return when {
            blockName.contains("observer") && facing != null -> {
                listOf(rotationForNearestLookingDirection(facing))
            }
            (blockName.contains("piston") ||
                    blockName.contains("dispenser") ||
                    blockName.contains("dropper")) && facing != null -> {
                listOf(rotationForNearestLookingDirection(facing.opposite))
            }
            (blockName.contains("repeater") || blockName.contains("comparator")) && facing != null -> {
                if (facing.axis.isHorizontal) listOf(Rotation(yawForDirection(facing.opposite), 0f)) else emptyList()
            }
            blockName.contains("crafter") -> {
                val orientationProp = state.properties.find { it.name == "orientation" } ?: return null
                val orientation = state.getValue(orientationProp) as? FrontAndTop ?: return null
                listOf(rotationForOrientation(orientation))
            }
            else -> null
        }
    }

    private fun rotationForOrientation(orientation: FrontAndTop): Rotation {
        val front = orientation.front()
        val top = orientation.top()
        return if (front.axis.isHorizontal) {
            rotationForNearestLookingDirection(front.opposite)
        } else {
            Rotation(yawForDirection(top.opposite), rotationForNearestLookingDirection(front.opposite).pitch)
        }
    }

    private fun simulatePlacementState(
        block: PlaceBlock,
        stack: ItemStack,
        hit: BlockHitResult,
        rotation: Rotation
    ): BlockState? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val blockItem = stack.item as? BlockItem ?: return null
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val oldHeadYaw = player.yHeadRot
        val oldBodyYaw = player.yBodyRot
        return try {
            player.yRot = rotation.yaw
            player.xRot = rotation.pitch
            player.yHeadRot = rotation.yaw
            player.yBodyRot = rotation.yaw
            val baseContext = BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit)
            val context = blockItem.updatePlacementContext(baseContext) ?: return null
            if (context.clickedPos != block.pos || !context.canPlace()) return null

            val predicted = runCatching {
                val method = BlockItem::class.java.getDeclaredMethod("getPlacementState", BlockPlaceContext::class.java)
                method.isAccessible = true
                method.invoke(blockItem, context) as? BlockState
            }.getOrNull() ?: blockItem.block.getStateForPlacement(context) ?: return null

            if (!predicted.canSurvive(level, block.pos)) return null
            if (!level.isUnobstructed(predicted, block.pos, CollisionContext.of(player))) return null
            predicted
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
            player.yHeadRot = oldHeadYaw
            player.yBodyRot = oldBodyYaw
        }
    }

    private fun findBlockSlot(item: Item): Int {
        if (item == Items.AIR) return Inventory.INVALID_SLOT
        val hotbar = Inventory.find(0 until Inventory.HOTBAR_SIZE) { stack ->
            stack.item == item && stack.item is BlockItem
        }
        if (hotbar.found) return hotbar.slot
        if (!context.inventorySwap) return Inventory.INVALID_SLOT

        val inventory = Inventory.find(Inventory.HOTBAR_SIZE until Inventory.MAIN_SIZE) { stack ->
            stack.item == item && stack.item is BlockItem
        }
        return if (inventory.found) inventory.slot else Inventory.INVALID_SLOT
    }

    private fun isInteractableBlock(block: Block): Boolean {
        val name = BuiltInRegistries.BLOCK.getKey(block).path
        return name.contains("chest") ||
                name.contains("shulker_box") ||
                name.contains("furnace") ||
                name.contains("hopper") ||
                name.contains("dropper") ||
                name.contains("dispenser") ||
                name.contains("anvil") ||
                name.contains("door") ||
                name.contains("trapdoor") ||
                name.contains("gate") ||
                name.contains("button") ||
                name.contains("lever") ||
                name.contains("table") ||
                name.contains("barrel") ||
                name.contains("campfire") ||
                name.contains("beacon") ||
                name.contains("brewing_stand") ||
                name.contains("lectern") ||
                name.contains("jukebox") ||
                name.contains("loom") ||
                name.contains("grindstone") ||
                name.contains("stonecutter") ||
                name.contains("cartography_table") ||
                name.contains("smoker") ||
                name.contains("blast_furnace")
    }

    private fun rotationForDirection(direction: Direction): Rotation {
        return when (direction) {
            Direction.NORTH -> Rotation(180f, 0f)
            Direction.SOUTH -> Rotation(0f, 0f)
            Direction.EAST -> Rotation(-90f, 0f)
            Direction.WEST -> Rotation(90f, 0f)
            Direction.UP -> Rotation(0f, -90f)
            Direction.DOWN -> Rotation(0f, 90f)
        }
    }

    private fun rotationForNearestLookingDirection(direction: Direction): Rotation {
        return when (direction) {
            Direction.UP -> Rotation(0f, -89.9f)
            Direction.DOWN -> Rotation(0f, 89.9f)
            else -> Rotation(yawForDirection(direction), 0f)
        }
    }

    private fun yawForDirection(direction: Direction): Float = rotationForDirection(direction).yaw

    private fun isRedstoneOrientationBlock(state: BlockState): Boolean {
        val blockName = BuiltInRegistries.BLOCK.getKey(state.block).path
        return REDSTONE_ORIENTATION_BLOCK_KEYWORDS.any { blockName.contains(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun samePropertyValue(actual: BlockState, expected: BlockState, property: Property<*>): Boolean {
        val typed = property as Property<Comparable<Any>>
        return actual.getValue(typed) == expected.getValue(typed)
    }

    private fun hasEntityBlocking(box: AABB): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        return level.getEntities(player, box) { entity ->
            !entity.isRemoved && entity.type == EntityType.PLAYER
        }.isNotEmpty()
    }

    private fun BlockState.canClick(): Boolean {
        return !isAir && !canBeReplaced()
    }

    data class PlaceBlock(val pos: BlockPos, val state: BlockState)

    data class PlacementPlan(val hit: BlockHitResult, val rotation: Rotation)

    companion object {
        val SUPPORT_DIRECTION_ORDER = listOf(
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.DOWN
        )
        val HORIZONTAL_ROTATION_DIRECTIONS = listOf(
            Direction.SOUTH,
            Direction.WEST,
            Direction.NORTH,
            Direction.EAST
        )
        val PLACEMENT_PROPERTY_NAMES = setOf(
            "facing",
            "axis",
            "half",
            "type",
            "face",
            "orientation",
            "vertical_direction",
            "hinge",
            "part",
            "rotation"
        )
        val REDSTONE_ORIENTATION_PROPERTY_NAMES = setOf(
            "facing",
            "orientation",
            "face"
        )
        val REDSTONE_ORIENTATION_BLOCK_KEYWORDS = setOf(
            "observer",
            "piston",
            "dispenser",
            "dropper",
            "repeater",
            "comparator",
            "hopper",
            "crafter",
            "lever",
            "button",
            "redstone_torch"
        )
    }
}
