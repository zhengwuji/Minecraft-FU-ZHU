package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.EventPreSync
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.manager.ANFriendManager
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.inventory.SilentSwapType
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
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BedItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color

class ANAutoBed : ANBaseModule(
    name = "AutoBed",
    description = "在末地或地狱自动放置并引爆床攻击敌人",
    category = ANModuleCategory.COMBAT,
    chineseName = "自动炸床",
    defaultState = ANModuleState.DISABLED
), ANWorldRenderModule {
    val targetRange = addSetting(ANSetting("TargetRange", 8.0f, 1.0f, 12.0f))
    val range = addSetting(ANSetting("Range", 5.0f, 2.0f, 6.0f))
    val placeDelay = addSetting(ANSetting("PlaceDelay", 100, 0, 1000))
    val explodeDelay = addSetting(ANSetting("ExplodeDelay", 50, 0, 1000))
    val dimensionCheck = addSetting(ANSetting("DimensionCheck", true))
    val rotate = addSetting(ANSetting("Rotate", RotateMode.SILENT))
    val swing = addSetting(ANSetting("Swing", true))
    val autoSwap = addSetting(ANSetting("AutoSwap", true))
    val silentSwap = addSetting(ANSetting("SilentSwap", true) { autoSwap.value })
    val swapBack = addSetting(ANSetting("SwapBack", true) { autoSwap.value })
    val render = addSetting(ANSetting("Render", true))
    val renderColor = addSetting(ANSetting("RenderColor", ColorGroupSetting(Color(0x99E85A5A.toInt(), true).rgb)) { render.value })

    private var target: Player? = null
    private var plan: BedPlan? = null
    private var currentAction: BedAction? = null
    private var lastPlaceTime = 0L
    private var lastExplodeTime = 0L

    enum class RotateMode {
        OFF,
        NORMAL,
        SILENT
    }

    override fun onDisable() {
        clear()
        Inventory.endSwap()
        Inventory.swapBack()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        disable()
    }

    override fun onTick() {
        val player = mc.player ?: return clear()
        if (mc.level == null) return clear()
        if (player.isSpectator || player.isCreative) return clear()

        target = findTarget() ?: return clear()
        val nextPlan = plan?.takeIf { it.isStillRelevant() } ?: createPlan() ?: return clear()
        plan = nextPlan

        if (dimensionCheck.value && !canBedExplode(nextPlan.bedHead)) {
            mc.gui.chat.addMessage(Component.literal("§c[AutoBed] Beds do not explode in this dimension. Disabling."))
            disable()
            return
        }

        currentAction = resolveAction(nextPlan)
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onPreSync(event: EventPreSync) {
        val action = currentAction ?: return
        val player = mc.player ?: return clear()
        if (mc.level == null || player.isSpectator || player.isCreative) return clear()
        if (!isValidTarget(target)) return clear()

        rotateTo(event, action)
        if (runAction(action)) {
            currentAction = null
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!render.value) return
        val level = mc.level ?: return
        if (!isValidTarget(target)) return
        val activePlan = plan ?: return
        val color = renderColor.value.toANColor()
        val boxes = mutableListOf<AABB>()

        if (!level.getBlockState(activePlan.obsidianLeft).`is`(Blocks.OBSIDIAN) && canUseObsidianPosition(activePlan.obsidianLeft)) {
            boxes += AABB(activePlan.obsidianLeft)
        }

        if (!level.getBlockState(activePlan.obsidianRight).`is`(Blocks.OBSIDIAN) && canUseObsidianPosition(activePlan.obsidianRight)) {
            boxes += AABB(activePlan.obsidianRight)
        }

        if (isObsidianReady(activePlan) && !isBedReady(activePlan) && canPlaceBedSpace(activePlan)) {
            boxes += AABB(activePlan.bedFoot).deflate(0.0, 0.45, 0.0)
            boxes += AABB(activePlan.bedHead).deflate(0.0, 0.45, 0.0)
        }

        for (box in boxes) {
            ANRender3DEngine.box(context, box.inflate(0.02), color.withAlpha(255), color.withAlpha(45))
        }
    }

    private fun resolveAction(plan: BedPlan): BedAction? {
        val level = mc.level ?: return null
        val now = System.currentTimeMillis()
        if (!plan.matchesPlayerY()) return null

        if (!level.getBlockState(plan.obsidianLeft).`is`(Blocks.OBSIDIAN)) {
            if (now - lastPlaceTime < placeDelay.value) return null
            val hit = supportHitResult(plan.obsidianLeft) ?: return null
            return BedAction(ActionType.PLACE_OBSIDIAN, plan, plan.obsidianLeft, hit, Vec3.atCenterOf(plan.obsidianLeft))
        }

        if (!level.getBlockState(plan.obsidianRight).`is`(Blocks.OBSIDIAN)) {
            if (now - lastPlaceTime < placeDelay.value) return null
            val hit = supportHitResult(plan.obsidianRight) ?: return null
            return BedAction(ActionType.PLACE_OBSIDIAN, plan, plan.obsidianRight, hit, Vec3.atCenterOf(plan.obsidianRight))
        }

        if (!isBedReady(plan)) {
            if (now - lastPlaceTime < placeDelay.value) return null
            if (!canPlaceBedSpace(plan)) return null
            return BedAction(ActionType.PLACE_BED, plan, plan.bedFoot, bedPlaceHit(plan), Vec3.atCenterOf(plan.bedFoot))
        }

        if (now - lastExplodeTime < explodeDelay.value) return null
        return BedAction(ActionType.EXPLODE_BED, plan, plan.bedFoot, bedExplodeHit(plan), Vec3.atCenterOf(plan.bedFoot))
    }

    private fun runAction(action: BedAction): Boolean {
        return when (action.type) {
            ActionType.PLACE_OBSIDIAN -> {
                val slot = findSlot { it.`is`(Items.OBSIDIAN) }
                if (slot == Inventory.INVALID_SLOT) return false
                useSlot(slot) {
                    interact(action.hit)
                    lastPlaceTime = System.currentTimeMillis()
                }
            }

            ActionType.PLACE_BED -> {
                val slot = findSlot { it.item is BedItem }
                if (slot == Inventory.INVALID_SLOT) return false
                useSlot(slot) {
                    interact(action.hit)
                    lastPlaceTime = System.currentTimeMillis()
                }
            }

            ActionType.EXPLODE_BED -> {
                interact(action.hit)
                lastExplodeTime = System.currentTimeMillis()
                plan = null
                true
            }
        }
    }

    private fun useSlot(slot: Int, action: () -> Unit): Boolean {
        val player = mc.player ?: return false
        if (!autoSwap.value && slot != player.inventory.selected) return false

        val type = if (slot in 0 until Inventory.HOTBAR_SIZE) SilentSwapType.HOTBAR else SilentSwapType.INVENTORY
        val selected = player.inventory.selected
        val swapped = when {
            slot == selected -> true
            silentSwap.value || type == SilentSwapType.INVENTORY -> Inventory.startSwap(slot, type)
            else -> Inventory.swap(slot, swapBack.value)
        }
        if (!swapped) return false

        try {
            action()
        } finally {
            if (slot != selected) {
                if (silentSwap.value || type == SilentSwapType.INVENTORY) {
                    Inventory.endSwap(type)
                } else if (swapBack.value) {
                    Inventory.swapBack()
                }
            }
        }
        return true
    }

    private fun interact(hit: BlockHitResult) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        if (swing.value) player.swing(InteractionHand.MAIN_HAND)
    }

    private fun rotateTo(event: EventPreSync, action: BedAction) {
        val player = mc.player ?: return
        if (rotate.value == RotateMode.OFF) return

        val rotations = RotationUtil.getRotationsTo(player.eyePosition, action.rotateVec)
        val yaw = if (action.type == ActionType.PLACE_BED) action.plan.bedDirection.toYRot() else rotations[0]
        val pitch = rotations[1]
        val oldYaw = player.yRot
        val oldPitch = player.xRot

        when (rotate.value) {
            RotateMode.NORMAL -> {
                player.yRot = yaw
                player.xRot = pitch
                player.yHeadRot = yaw
            }

            RotateMode.SILENT -> {
                player.yRot = yaw
                player.xRot = pitch
                player.yHeadRot = yaw
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

    private fun createPlan(): BedPlan? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val direction = Direction.fromYRot(player.yRot.toDouble())
        val bedDirection = direction.clockWise
        val playerPos = player.blockPosition()
        val obsidianLeft = playerPos.relative(direction)
        val obsidianRight = obsidianLeft.relative(bedDirection)
        val bedFoot = obsidianLeft.relative(direction)
        val bedHead = obsidianRight.relative(direction)
        val plan = BedPlan(bedDirection, obsidianLeft, obsidianRight, bedFoot, bedHead)

        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(bedHead)) > range.value * range.value) return null
        if (!level.isInWorldBounds(obsidianLeft) || !level.isInWorldBounds(obsidianRight)) return null
        if (!level.isInWorldBounds(bedFoot) || !level.isInWorldBounds(bedHead)) return null
        if (!plan.matchesY(playerPos.y)) return null
        if (!canUseObsidianPosition(obsidianLeft) || !canUseObsidianPosition(obsidianRight)) return null
        return plan
    }

    private fun BedPlan.isStillRelevant(): Boolean {
        val level = mc.level ?: return false
        if (!matchesPlayerY()) return false
        if (!level.isInWorldBounds(obsidianLeft) || !level.isInWorldBounds(obsidianRight)) return false
        if (!level.isInWorldBounds(bedFoot) || !level.isInWorldBounds(bedHead)) return false
        if (!canUseObsidianPosition(obsidianLeft) || !canUseObsidianPosition(obsidianRight)) return false
        if (isBedReady(this)) return true
        return canPlaceBedSpace(this)
    }

    private fun canUseObsidianPosition(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(pos)
        if (state.`is`(Blocks.OBSIDIAN)) return true
        if (!state.canBeReplaced()) return false
        return !hasEntityBlocking(AABB(pos)) && supportHitResult(pos) != null
    }

    private fun findTarget(): Player? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val rangeSq = targetRange.value * targetRange.value

        return level.players()
            .asSequence()
            .filter { it !== player && isValidTarget(it) }
            .filter { player.distanceToSqr(it) <= rangeSq }
            .minByOrNull { player.distanceToSqr(it) }
    }

    private fun isValidTarget(player: Player?): Boolean {
        if (player == null) return false
        return player.isAlive &&
            !player.isSpectator &&
            !player.isCreative &&
            !ANFriendManager.isFriend(player.name.string)
    }

    private fun canPlaceBedSpace(plan: BedPlan): Boolean {
        val level = mc.level ?: return false
        val footState = level.getBlockState(plan.bedFoot)
        val headState = level.getBlockState(plan.bedHead)
        if (footState.block is BedBlock || headState.block is BedBlock) return true
        if (!isObsidianReady(plan)) return false
        if (!footState.canBeReplaced() || !headState.canBeReplaced()) return false
        if (!canClick(level.getBlockState(plan.bedFoot.below()))) return false
        if (!canClick(level.getBlockState(plan.bedHead.below()))) return false
        return !hasEntityBlocking(AABB(plan.bedFoot)) && !hasEntityBlocking(AABB(plan.bedHead))
    }

    private fun isBedReady(plan: BedPlan): Boolean {
        val level = mc.level ?: return false
        return level.getBlockState(plan.bedFoot).block is BedBlock || level.getBlockState(plan.bedHead).block is BedBlock
    }

    private fun isObsidianReady(plan: BedPlan): Boolean {
        val level = mc.level ?: return false
        return level.getBlockState(plan.obsidianLeft).`is`(Blocks.OBSIDIAN) &&
            level.getBlockState(plan.obsidianRight).`is`(Blocks.OBSIDIAN)
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

    private fun bedPlaceHit(plan: BedPlan): BlockHitResult {
        val support = plan.bedFoot.below()
        val hitVec = Vec3(support.x + 0.5, support.y + 1.0, support.z + 0.5)
        return BlockHitResult(hitVec, Direction.UP, support, false)
    }

    private fun bedExplodeHit(plan: BedPlan): BlockHitResult {
        val pos = if (mc.level?.getBlockState(plan.bedFoot)?.block is BedBlock) plan.bedFoot else plan.bedHead
        return BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
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

    private fun canBedExplode(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        return !level.dimensionType().bedWorks()
    }

    private fun findSlot(predicate: (ItemStack) -> Boolean): Int {
        val result = Inventory.find(0 until Inventory.MAIN_SIZE, predicate)
        return if (result.found) result.slot else Inventory.INVALID_SLOT
    }

    private fun clear() {
        target = null
        plan = null
        currentAction = null
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class BedPlan(
        val bedDirection: Direction,
        val obsidianLeft: BlockPos,
        val obsidianRight: BlockPos,
        val bedFoot: BlockPos,
        val bedHead: BlockPos
    ) {
        fun matchesPlayerY(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            return matchesY(player.blockPosition().y)
        }

        fun matchesY(y: Int): Boolean {
            return obsidianLeft.y == y && obsidianRight.y == y && bedFoot.y == y && bedHead.y == y
        }
    }

    private data class BedAction(
        val type: ActionType,
        val plan: BedPlan,
        val pos: BlockPos,
        val hit: BlockHitResult,
        val rotateVec: Vec3
    )

    private enum class ActionType {
        PLACE_OBSIDIAN,
        PLACE_BED,
        EXPLODE_BED
    }
}

