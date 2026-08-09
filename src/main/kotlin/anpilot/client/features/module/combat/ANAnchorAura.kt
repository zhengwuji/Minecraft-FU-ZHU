package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.EventPreSync
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.manager.ANFriendManager
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.utility.ExplosionUtils
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.ceil

class ANAnchorAura : ANBaseModule(
    name = "AnchorAura",
    description = "自动充能并引爆重生锚攻击敌人",
    category = ANModuleCategory.COMBAT,
    chineseName = "重生锚光环",
    defaultState = ANModuleState.DISABLED
), ANWorldRenderModule {
    val timeout = addSetting(ANSetting("Timeout", 250, 50, 2000))

    val maxSelfPlace = addSetting(ANSetting("MaxSelfPlace", 8.0f, 0.0f, 36.0f))
    val minDamage = addSetting(ANSetting("MinDamage", 7.0f, 0.0f, 36.0f))

    val maxSelfBreak = addSetting(ANSetting("MaxSelfBreak", 10.0f, 0.0f, 36.0f))
    val minBreakDamage = addSetting(ANSetting("MinBreakDmg", 2.0f, 0.0f, 36.0f))

    val targetRange = addSetting(ANSetting("Range", 8.0f, 0.0f, 12.0f))
    val anchorRange = addSetting(ANSetting("AnchorRange", 4.0f, 0.0f, 6.0f))
    val ignoreTerrain = addSetting(ANSetting("IgnoreTerrain", true))
    val extrapolate = addSetting(ANSetting("Extrapolate", 0, 0, 20))

    val placeDelay = addSetting(ANSetting("PlaceDelay", 100, 0, 1000))
    val breakDelay = addSetting(ANSetting("BreakDelay", 100, 0, 1000))
    val multitask = addSetting(ANSetting("Multitask", true))
    val swing = addSetting(ANSetting("Swing", true))
    val rotate = addSetting(ANSetting("Rotate", RotateMode.OFF))

    val autoSwap = addSetting(ANSetting("AutoSwap", true))
    val silentSwap = addSetting(ANSetting("SilentSwap", false) { autoSwap.value })
    val swapBack = addSetting(ANSetting("SwapBack", true) { autoSwap.value && !silentSwap.value })

    val render = addSetting(ANSetting("Render", true))
    val renderColor = addSetting(ANSetting("RenderColor", ColorGroupSetting(Color(0x99FF6A00.toInt(), true).rgb)) {
        render.value
    })

    private val placedAnchors = LinkedHashMap<BlockPos, Long>()
    private var currentAction: AnchorAction? = null
    private var lastPlaceTime = 0L
    private var lastBreakTime = 0L

    enum class RotateMode {
        OFF,
        NORMAL,
        SILENT
    }

    override fun onDisable() {
        currentAction = null
        placedAnchors.clear()
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
        if (player.isUsingItem && !multitask.value) return clear()
        if (!canAnchorsExplode()) return clear()

        prunePlacedAnchors()
        val target = findTarget() ?: return clear()
        currentAction = findBestAction(target)
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onPreSync(event: EventPreSync) {
        val action = currentAction ?: return
        val player = mc.player ?: return clear()
        if (mc.level == null || player.isSpectator || player.isCreative) return clear()
        if (player.isUsingItem && !multitask.value) return clear()

        rotateTo(event, action.hitVec)
        if (runAction(action)) {
            currentAction = null
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!render.value) return
        val action = currentAction ?: return
        val color = renderColor.value.toANColor()
        ANRender3DEngine.box(
            context,
            AABB(action.pos).inflate(0.02),
            color.withAlpha(255),
            color.withAlpha(45)
        )
    }

    private fun findTarget(): Player? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val rangeSq = targetRange.value * targetRange.value

        return level.players()
            .asSequence()
            .filter { it !== player && it.isAlive && !it.isSpectator && !it.isCreative }
            .filter { !ANFriendManager.isFriend(it.name.string) }
            .filter { player.distanceToSqr(it) <= rangeSq }
            .minByOrNull { player.distanceToSqr(it) }
    }

    private fun findBestAction(target: Player): AnchorAction? {
        val charged = findBestExistingAnchor(target, requireCharged = true)
        if (charged != null && canBreakNow()) return charged

        val uncharged = findBestExistingAnchor(target, requireCharged = false)
        if (uncharged != null && canPlaceNow()) return uncharged

        val place = findBestPlacement(target)
        if (place != null && canPlaceNow()) return place

        return charged ?: uncharged ?: place
    }

    private fun findBestExistingAnchor(target: Player, requireCharged: Boolean): AnchorAction? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val rangeSq = anchorRange.value * anchorRange.value
        var best: AnchorAction? = null

        for (pos in scanPositions()) {
            if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue
            val state = level.getBlockState(pos)
            if (!state.`is`(Blocks.RESPAWN_ANCHOR)) continue
            val charges = state.getValue(RespawnAnchorBlock.CHARGE)
            if (requireCharged && charges <= 0) continue
            if (!requireCharged && charges > 0) continue

            val damage = evaluate(pos, target, maxSelfBreak.value, minBreakDamage.value) ?: continue
            val type = if (requireCharged) ActionType.EXPLODE else ActionType.CHARGE
            val action = AnchorAction(pos, type, target, damage.targetDamage, damage.selfDamage)
            if (best == null || action.score > best.score) best = action
        }

        return best
    }

    private fun findBestPlacement(target: Player): AnchorAction? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val anchorSlot = getAnchorSlot()
        if (anchorSlot == Inventory.INVALID_SLOT) return null

        val rangeSq = anchorRange.value * anchorRange.value
        var best: AnchorAction? = null

        for (pos in scanPositions()) {
            if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue
            if (!canPlaceAnchorAt(pos)) continue
            val state = level.getBlockState(pos)
            if (!state.canBeReplaced()) continue

            val damage = evaluate(pos, target, maxSelfPlace.value, minDamage.value) ?: continue
            val action = AnchorAction(pos, ActionType.PLACE, target, damage.targetDamage, damage.selfDamage)
            if (best == null || action.score > best.score) best = action
        }

        return best
    }

    private fun evaluate(
        pos: BlockPos,
        target: Player,
        maxSelfDamage: Float,
        minTargetDamage: Float
    ): DamageData? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val explosion = Vec3.atCenterOf(pos)
        val ignoredBlocks = setOf(pos)
        val selfDamage = ExplosionUtils.damageToEntity(level, player, explosion, ANCHOR_POWER, ignoreTerrain.value, ignoredBlocks)
        if (selfDamage > maxSelfDamage) return null
        if (player.getHealth() + player.absorptionAmount - selfDamage < 0.5f) return null

        val targetMotion = target.deltaMovement.scale(extrapolate.value.toDouble())
        val targetDamage = ExplosionUtils.damageToEntity(
            level,
            target,
            target.position().add(targetMotion.x, 0.0, targetMotion.z),
            target.boundingBox.move(targetMotion.x, 0.0, targetMotion.z),
            explosion,
            ANCHOR_POWER,
            ignoreTerrain.value,
            ignoredBlocks
        )
        if (targetDamage <= 0.0f) return null

        val lethal = targetDamage >= target.getHealth() + target.absorptionAmount
        if (!lethal && targetDamage < minTargetDamage) return null

        return DamageData(targetDamage, selfDamage)
    }

    private fun runAction(action: AnchorAction): Boolean {
        return when (action.type) {
            ActionType.PLACE -> placeAnchor(action)
            ActionType.CHARGE -> chargeAnchor(action)
            ActionType.EXPLODE -> explodeAnchor(action)
        }
    }

    private fun placeAnchor(action: AnchorAction): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPlaceTime < placeDelay.value) return false
        val hit = supportHitResult(action.pos) ?: return false
        val slot = getAnchorSlot()
        if (slot == Inventory.INVALID_SLOT) return false

        return useSlot(slot) {
            interact(hit)
            placedAnchors[action.pos] = now
            lastPlaceTime = now
        }
    }

    private fun chargeAnchor(action: AnchorAction): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPlaceTime < placeDelay.value) return false
        val slot = getGlowstoneSlot()
        if (slot == Inventory.INVALID_SLOT) return false

        return useSlot(slot) {
            interact(anchorHitResult(action.pos))
            placedAnchors[action.pos] = now
            lastPlaceTime = now
        }
    }

    private fun explodeAnchor(action: AnchorAction): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBreakTime < breakDelay.value) return false
        val slot = getExplodeSlot()
        if (slot == Inventory.INVALID_SLOT) return false

        return useSlot(slot) {
            interact(anchorHitResult(action.pos))
            placedAnchors.remove(action.pos)
            lastBreakTime = now
        }
    }

    private fun useSlot(slot: Int, action: () -> Unit): Boolean {
        val player = mc.player ?: return false
        val swapped = when {
            slot == player.inventory.selected -> true
            silentSwap.value -> Inventory.startSwap(slot)
            autoSwap.value -> Inventory.swap(slot, swapBack.value)
            else -> false
        }
        if (!swapped) return false

        try {
            action()
        } finally {
            if (slot != player.inventory.selected) {
                if (silentSwap.value) {
                    Inventory.endSwap()
                } else if (autoSwap.value && swapBack.value) {
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
        if (swing.value) {
            player.swing(InteractionHand.MAIN_HAND)
        }
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

    private fun canPlaceAnchorAt(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
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

    private fun anchorHitResult(pos: BlockPos): BlockHitResult {
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

    private fun getAnchorSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Blocks.RESPAWN_ANCHOR.asItem())) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun getGlowstoneSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.GLOWSTONE)) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun getExplodeSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        val selected = player.inventory.selected
        val selectedStack = player.inventory.getItem(selected)
        if (!selectedStack.`is`(Items.GLOWSTONE)) return selected

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && !stack.`is`(Items.GLOWSTONE) && stack.item !is BlockItem) {
                return slot
            }
        }

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && !stack.`is`(Items.GLOWSTONE)) {
                return slot
            }
        }

        return Inventory.INVALID_SLOT
    }

    private fun scanPositions(): Sequence<BlockPos> {
        val player = mc.player ?: return emptySequence()
        val radius = ceil(anchorRange.value.toDouble()).toInt()
        val origin = player.blockPosition()
        return sequence {
            for (x in -radius..radius) {
                for (y in -radius..radius) {
                    for (z in -radius..radius) {
                        yield(origin.offset(x, y, z))
                    }
                }
            }
        }
    }

    private fun canPlaceNow(): Boolean = System.currentTimeMillis() - lastPlaceTime >= placeDelay.value

    private fun canBreakNow(): Boolean = System.currentTimeMillis() - lastBreakTime >= breakDelay.value

    private fun canAnchorsExplode(): Boolean {
        val level = mc.level ?: return false
        return level.dimension() != Level.NETHER
    }

    private fun prunePlacedAnchors() {
        val now = System.currentTimeMillis()
        val iterator = placedAnchors.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > timeout.value) iterator.remove()
        }
    }

    private fun clear() {
        currentAction = null
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class DamageData(val targetDamage: Float, val selfDamage: Float)

    private data class AnchorAction(
        val pos: BlockPos,
        val type: ActionType,
        val target: Player,
        val targetDamage: Float,
        val selfDamage: Float
    ) {
        val hitVec: Vec3 = Vec3.atCenterOf(pos)
        val score: Float = targetDamage - selfDamage * 0.35f
    }

    private enum class ActionType {
        PLACE,
        CHARGE,
        EXPLODE
    }

    private companion object {
        private const val ANCHOR_POWER = 10.0f
    }
}
