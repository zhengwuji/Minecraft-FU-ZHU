package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.EventEntitySpawnPost
import anpilot.client.features.event.impl.EventPreSync
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.manager.ANFriendManager
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.module.player.ANPacketMine
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.utility.CrystalUtils
import anpilot.client.features.utility.ExplosionUtils
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.AmbientCreature
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.WaterAnimal
import net.minecraft.world.entity.animal.AbstractGolem
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.npc.AbstractVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.ceil


class ANAutoCrystal : ANBaseModule(
    name = "AutoCrystal",
    description = "自动放置并引爆末影水晶攻击附近目标",
    category = ANModuleCategory.COMBAT,
    chineseName = "自动水晶",
    defaultState = ANModuleState.DISABLED
), ANWorldRenderModule {
    val multitask = addSetting(ANSetting("Multitask", true))
    val swing = addSetting(ANSetting("Swing", true))

    val targetRange = addSetting(ANSetting("TargetRange", 10.0f, 1.0f, 15.0f))
    val targetPlayers = addSetting(ANSetting("Players", true))
    val targetNakeds = addSetting(ANSetting("Nakeds", true) { targetPlayers.value })
    val targetHostiles = addSetting(ANSetting("Hostiles", false))
    val targetPassives = addSetting(ANSetting("Passives", false))

    val breakRange = addSetting(ANSetting("BreakRange", 4.0f, 1.0f, 6.0f))
    val breakDelay = addSetting(ANSetting("BreakDelay", 100, 0, 1000))
    val minExisted = addSetting(ANSetting("MinExisted", 0, 0, 10))

    val placeRange = addSetting(ANSetting("PlaceRange", 4.0f, 1.0f, 6.0f))
    val placeDelay = addSetting(ANSetting("PlaceDelay", 100, 0, 1000))
    val placeLimit = addSetting(ANSetting("PlaceLimit", 2, 1, 10))
    val protocolPlace = addSetting(ANSetting("Protocol", false))
    val basePlace = addSetting(ANSetting("Support", false))

    val instantBreak = addSetting(ANSetting("InstantBreak", false))
    val instantPlace = addSetting(ANSetting("InstantPlace", false))
    val predictAttack = addSetting(ANSetting("PredictAttack", false))
    val predictPlace = addSetting(ANSetting("PredictPlace", Timing.OFF))
    val cevBreak = addSetting(ANSetting("CevBreak", false))
    val targetItems = addSetting(ANSetting("TargetItems", false))
    val prePlace = addSetting(ANSetting("PrePlace", 5, 0, 10) { targetItems.value || predictPlace.value != Timing.OFF })

    val minDamage = addSetting(ANSetting("MinDamage", 4.0f, 2.0f, 10.0f))
    val maxSelfDamage = addSetting(ANSetting("MaxSelfDamage", 12.0f, 2.0f, 20.0f))
    val lethalOverride = addSetting(ANSetting("Override", true))
    val ignoreTerrain = addSetting(ANSetting("IgnoreTerrain", false))

    val rotate = addSetting(ANSetting("Rotate", RotateMode.OFF))

    val autoSwap = addSetting(ANSetting("AutoSwap", false))
    val silentSwap = addSetting(ANSetting("SilentSwap", false) { autoSwap.value })
    val swapBack = addSetting(ANSetting("SwapBack", false) { autoSwap.value && !silentSwap.value })
    val antiWeakness = addSetting(ANSetting("AntiWeakness", false) { autoSwap.value && silentSwap.value })

    val renderPlace = addSetting(ANSetting("RenderPlace", true))
    val renderColor = addSetting(ANSetting("RenderColor", ColorGroupSetting(Color(0x99FF4A4A.toInt(), true).rgb)) {
        renderPlace.value
    })

    private var currentAttack: AttackData? = null
    private var currentPlace: PlaceData? = null
    private var lastAttackTime = 0L
    private var lastPlaceTime = 0L
    private var placementsThisTick = 0
    private var highestEntityId = 0
    private val placedBases = LinkedHashMap<BlockPos, Long>()

    enum class RotateMode {
        OFF,
        NORMAL,
        SILENT
    }

    enum class Timing {
        TICK,
        INSTANT,
        OFF
    }

    override fun onDisable() {
        currentAttack = null
        currentPlace = null
        placementsThisTick = 0
        placedBases.clear()
        Inventory.endSwap()
        Inventory.swapBack()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        disable()
    }

    override fun onTick() {
        placementsThisTick = 0

        val player = mc.player ?: return clear()
        if (mc.level == null) return clear()
        if (player.isSpectator || player.isUsingItem && !multitask.value) return clear()

        val target = findTarget() ?: return clear()
        currentAttack = findBestAttackCrystal(target)
        currentPlace = findBestPlacement(target)

        if (currentPlace == null && predictPlace.value == Timing.TICK) {
            currentPlace = findMiningPrediction(target, requireReady = false)
        }
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onPreSync(event: EventPreSync) {
        val player = mc.player ?: return
        if (mc.level == null || player.isSpectator || player.isUsingItem && !multitask.value) return

        val attack = currentAttack
        if (attack != null) {
            rotateTo(event, attack.crystal.position().add(0.0, 0.5, 0.0))
            if (runAttack(attack) && instantPlace.value) {
                currentPlace?.let { runPlace(it, ignoreDelay = true) }
            }
        }

        val place = currentPlace
        if (place != null) {
            rotateTo(event, place.crystalVec)
            runPlace(place)
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!renderPlace.value) return
        val place = currentPlace ?: return
        val color = renderColor.value.toANColor()
        ANRender3DEngine.box(
            context,
            AABB(place.basePos).inflate(0.02),
            color.withAlpha(255),
            color.withAlpha(45)
        )
    }

    fun isRunning(): Boolean = enabled && (currentAttack != null || currentPlace != null)

    @ANEventHandler
    fun onEntitySpawn(event: EventEntitySpawnPost) {
        val crystal = event.entity as? EndCrystal ?: return
        highestEntityId = maxOf(highestEntityId, crystal.id)

        val basePos = crystal.blockPosition().below()
        if (instantBreak.value && placedBases.containsKey(basePos)) {
            val target = findTarget() ?: return
            val data = evaluateCrystal(crystal.position(), target) ?: return
            runAttack(AttackData(crystal, data.targetDamage, data.selfDamage), ignoreDelay = true)
        }

        if (instantPlace.value) {
            findTarget()?.let { target ->
                findBestPlacement(target)?.let { runPlace(it, ignoreDelay = true) }
            }
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val packet = event.packet

        if (packet is ClientboundRemoveEntitiesPacket) {
            packet.entityIds.forEach { highestEntityId = maxOf(highestEntityId, it) }
            if (instantPlace.value) {
                findTarget()?.let { target ->
                    findBestPlacement(target)?.let { runPlace(it, ignoreDelay = true) }
                }
            }
        }

        if (predictPlace.value == Timing.INSTANT) {
            when (packet) {
                is ClientboundBlockUpdatePacket -> {
                    if (packet.blockState.isAir) runInstantMiningPrediction(packet.pos)
                }
                is ClientboundSectionBlocksUpdatePacket -> {
                    packet.runUpdates { pos, state ->
                        if (state.isAir) runInstantMiningPrediction(pos)
                    }
                }
            }
        }
    }

    private fun runAttack(attack: AttackData, ignoreDelay: Boolean = false): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        val now = System.currentTimeMillis()
        if (!ignoreDelay && now - lastAttackTime < breakDelay.value) return false
        if (!attack.crystal.isAlive || attack.crystal.tickCount < minExisted.value) return false

        val weaknessSlot = if (antiWeakness.value) getAntiWeaknessSlot() else Inventory.INVALID_SLOT
        val swapped = if (weaknessSlot != Inventory.INVALID_SLOT) Inventory.startSwap(weaknessSlot) else true
        if (!swapped) return false

        try {
            gameMode.attack(player, attack.crystal)
            if (swing.value) {
                player.swing(InteractionHand.MAIN_HAND)
            }
        } finally {
            if (weaknessSlot != Inventory.INVALID_SLOT) Inventory.endSwap()
        }

        lastAttackTime = now
        return true
    }

    private fun runPlace(place: PlaceData, ignoreDelay: Boolean = false): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        val now = System.currentTimeMillis()
        if (!ignoreDelay && now - lastPlaceTime < placeDelay.value) return false
        if (placementsThisTick >= placeLimit.value) return false

        if (!isCrystalBase(place.basePos)) {
            if (!basePlace.value || !placeSupportBlock(place.basePos)) return false
            lastPlaceTime = now
            return true
        }

        val hand = getCrystalHand()
        val crystalSlot = if (hand == InteractionHand.MAIN_HAND) getCrystalHotbarSlot() else Inventory.INVALID_SLOT

        val swapped = when {
            hand == InteractionHand.OFF_HAND -> true
            player.mainHandItem.`is`(Items.END_CRYSTAL) -> true
            crystalSlot == Inventory.INVALID_SLOT -> false
            silentSwap.value -> Inventory.startSwap(crystalSlot)
            autoSwap.value -> Inventory.swap(crystalSlot, swapBack.value)
            else -> false
        }
        if (!swapped) return false

        try {
            gameMode.useItemOn(player, hand, CrystalUtils.hitResult(place.basePos))
            if (swing.value) {
                player.swing(hand)
            }
            placementsThisTick++
            lastPlaceTime = now
            placedBases[place.basePos] = now
            prunePlacedBases(now)

            if (predictAttack.value && highestEntityId > 0) {
                attackPredictedCrystal(highestEntityId + 1)
            }
        } finally {
            if (silentSwap.value) {
                Inventory.endSwap()
            } else if (autoSwap.value && swapBack.value) {
                Inventory.swapBack()
            }
        }

        return true
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

    private fun findTarget(): LivingEntity? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val rangeSq = targetRange.value * targetRange.value

        return level.entitiesForRendering()
            .asSequence()
            .mapNotNull { it as? LivingEntity }
            .filter { it !== player && it.isAlive && isValidTarget(it) }
            .filter { player.distanceToSqr(it) <= rangeSq }
            .minByOrNull { player.distanceToSqr(it) }
    }

    private fun findBestAttackCrystal(target: LivingEntity): AttackData? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val breakRangeSq = breakRange.value * breakRange.value
        var best: AttackData? = null

        for (entity in level.entitiesForRendering()) {
            val crystal = entity as? EndCrystal ?: continue
            if (!crystal.isAlive || crystal.tickCount < minExisted.value) continue
            if (player.distanceToSqr(crystal) > breakRangeSq) continue

            val data = evaluateCrystal(crystal.position(), target) ?: continue
            if (best == null || data.targetDamage > best.targetDamage) {
                best = AttackData(crystal, data.targetDamage, data.selfDamage)
            }
        }

        return best
    }

    private fun findBestPlacement(target: LivingEntity): PlaceData? {
        val player = mc.player ?: return null
        if (mc.level == null) return null
        val radius = ceil(placeRange.value.toDouble()).toInt()
        val origin = player.blockPosition()
        val rangeSq = placeRange.value * placeRange.value
        var best: PlaceData? = null

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val basePos = origin.offset(x, y, z)
                    val center = Vec3.atCenterOf(basePos)
                    if (player.distanceToSqr(center) > rangeSq) continue
                    if (!canUseOnBlock(basePos)) continue

                    val crystalVec = Vec3.atBottomCenterOf(basePos.above())
                    val data = evaluateCrystal(crystalVec, target) ?: continue
                    if (best == null || data.targetDamage > best.targetDamage) {
                        best = PlaceData(basePos, crystalVec, data.targetDamage, data.selfDamage)
                    }
                }
            }
        }

        if (cevBreak.value) {
            val cev = target.blockPosition().above(2)
            if (player.distanceToSqr(Vec3.atCenterOf(cev)) <= rangeSq && canUseOnBlock(cev)) {
                val crystalVec = Vec3.atBottomCenterOf(cev.above())
                val data = evaluateCrystal(crystalVec, target)
                if (data != null && (best == null || data.targetDamage > best.targetDamage)) {
                    best = PlaceData(cev, crystalVec, data.targetDamage, data.selfDamage)
                }
            }
        }

        return best
    }

    private fun findMiningPrediction(target: LivingEntity, requireReady: Boolean): PlaceData? {
        val packetMine = packetMineModule() ?: return null
        if (requireReady && !packetMine.isMainDoneMining()) return null
        val minedPos = packetMine.getMainMiningPos() ?: return null
        if (prePlace.value > 0 && !packetMine.hasMainMinedFor(prePlace.value) && !packetMine.isMainDoneMining()) return null

        val basePos = minedPos.below()
        val player = mc.player ?: return null
        if (player.distanceToSqr(Vec3.atCenterOf(basePos)) > placeRange.value * placeRange.value) return null
        if (!canUseOnBlock(basePos, ignoredBlock = minedPos)) return null

        val crystalVec = Vec3.atBottomCenterOf(basePos.above())
        val data = evaluateCrystal(crystalVec, target, ignoredBlock = minedPos) ?: return null
        return PlaceData(basePos, crystalVec, data.targetDamage, data.selfDamage)
    }

    private fun evaluateCrystal(crystalVec: Vec3, target: LivingEntity, ignoredBlock: BlockPos? = null): DamageData? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val ignored = if (ignoredBlock == null) emptySet() else setOf(ignoredBlock)

        val selfDamage = ExplosionUtils.crystalDamageToEntity(level, player, crystalVec, ignoreTerrain.value, ignored)
        if (selfDamage > maxSelfDamage.value) return null
        if (player.getHealth() + player.absorptionAmount - selfDamage < 0.5f) return null

        val targetDamage = ExplosionUtils.crystalDamageToEntity(level, target, crystalVec, ignoreTerrain.value, ignored)
        if (targetDamage <= 0.0f) return null

        val targetHealth = target.getHealth() + target.absorptionAmount
        val lethal = lethalOverride.value && targetDamage >= targetHealth
        if (!lethal && targetDamage < minDamage.value) return null

        return DamageData(targetDamage, selfDamage)
    }

    private fun canUseOnBlock(basePos: BlockPos, ignoredBlock: BlockPos? = null): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(basePos)
        if (!isCrystalBase(state)) {
            if (!basePlace.value || !canPlaceSupportAt(basePos)) return false
        }

        val airPos = basePos.above()
        val upperPos = basePos.above(2)
        if (airPos != ignoredBlock && !isReplaceableCrystalSpace(level.getBlockState(airPos))) return false
        if (protocolPlace.value && upperPos != ignoredBlock && !isReplaceableCrystalSpace(level.getBlockState(upperPos))) return false
        return !hasEntityBlockingCrystal(crystalBox(basePos), ignoreItems = targetItems.value)
    }

    private fun placeSupportBlock(basePos: BlockPos): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        if (!canPlaceSupportAt(basePos)) return false
        val hit = supportHitResult(basePos) ?: return false
        val slot = findObsidianHotbarSlot()

        val swapped = when {
            player.mainHandItem.`is`(Items.OBSIDIAN) -> true
            slot == Inventory.INVALID_SLOT -> false
            silentSwap.value -> Inventory.startSwap(slot)
            autoSwap.value -> Inventory.swap(slot, swapBack.value)
            else -> false
        }
        if (!swapped) return false

        try {
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            if (swing.value) player.swing(InteractionHand.MAIN_HAND)
        } finally {
            if (silentSwap.value) {
                Inventory.endSwap()
            } else if (autoSwap.value && swapBack.value) {
                Inventory.swapBack()
            }
        }
        return true
    }

    private fun attackPredictedCrystal(entityId: Int) {
        val level = mc.level ?: return
        val entity = level.getEntity(entityId)
        if (entity is EndCrystal) {
            val target = findTarget() ?: return
            evaluateCrystal(entity.position(), target)?.let {
                runAttack(AttackData(entity, it.targetDamage, it.selfDamage), ignoreDelay = true)
            }
        }
    }

    private fun runInstantMiningPrediction(updatedPos: BlockPos) {
        val target = findTarget() ?: return
        val prediction = findMiningPrediction(target, requireReady = true) ?: return
        if (updatedPos == prediction.basePos.above()) {
            runPlace(prediction, ignoreDelay = true)
        }
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        if (entity is Player) {
            if (!targetPlayers.value || entity.isSpectator || entity.isCreative) return false
            if (!targetNakeds.value && isNaked(entity)) return false
            return !ANFriendManager.isFriend(entity.name.string)
        }

        if (targetHostiles.value && entity is Monster) return true
        if (targetPassives.value && isPassive(entity)) return true
        return false
    }

    private fun isPassive(entity: Entity): Boolean {
        return entity is Animal ||
            entity is WaterAnimal ||
            entity is AmbientCreature ||
            entity is AbstractVillager ||
            entity is AbstractGolem
    }

    private fun isNaked(player: Player): Boolean {
        return player.getItemBySlot(EquipmentSlot.HEAD).isEmpty &&
            player.getItemBySlot(EquipmentSlot.CHEST).isEmpty &&
            player.getItemBySlot(EquipmentSlot.LEGS).isEmpty &&
            player.getItemBySlot(EquipmentSlot.FEET).isEmpty
    }

    private fun getCrystalHand(): InteractionHand {
        val player = mc.player ?: return InteractionHand.MAIN_HAND
        return if (player.offhandItem.`is`(Items.END_CRYSTAL)) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
    }

    private fun getCrystalHotbarSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.END_CRYSTAL)) return slot
        }
        return Inventory.INVALID_SLOT
    }

    private fun getAntiWeaknessSlot(): Int {
        val result = Inventory.getAntiWeaknessItem()
        return if (result.found) result.slot else Inventory.INVALID_SLOT
    }

    private fun findObsidianHotbarSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (stack.`is`(Items.OBSIDIAN) || stack.item is BlockItem && (stack.item as BlockItem).block == Blocks.OBSIDIAN) {
                return slot
            }
        }
        return Inventory.INVALID_SLOT
    }

    private fun canPlaceSupportAt(basePos: BlockPos): Boolean {
        val level = mc.level ?: return false
        if (!level.getBlockState(basePos).canBeReplaced()) return false
        if (hasEntityBlockingSupport(AABB(basePos))) return false
        return supportHitResult(basePos) != null
    }

    private fun supportHitResult(basePos: BlockPos): BlockHitResult? {
        val level = mc.level ?: return null
        for (direction in Direction.entries) {
            val neighbor = basePos.relative(direction.opposite)
            val state = level.getBlockState(neighbor)
            if (state.isAir || state.canBeReplaced()) continue
            return BlockHitResult(Vec3.atCenterOf(neighbor), direction, neighbor, false)
        }
        return null
    }

    private fun hasEntityBlockingSupport(box: AABB): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        return level.getEntities(player, box) { !it.isRemoved }.isNotEmpty()
    }

    private fun hasEntityBlockingCrystal(box: AABB, ignoreItems: Boolean): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        return level.getEntities(player, box) { entity ->
            entity.type != EntityType.EXPERIENCE_ORB &&
                entity.type != EntityType.END_CRYSTAL &&
                (!ignoreItems || entity.type != EntityType.ITEM)
        }.isNotEmpty()
    }

    private fun crystalBox(basePos: BlockPos): AABB =
        AABB(-0.5, 0.0, -0.5, 0.5, 2.0, 0.5).move(Vec3.atBottomCenterOf(basePos.above()))

    private fun isCrystalBase(pos: BlockPos): Boolean =
        mc.level?.getBlockState(pos)?.let(::isCrystalBase) == true

    private fun isCrystalBase(state: BlockState): Boolean =
        state.`is`(Blocks.OBSIDIAN) || state.`is`(Blocks.BEDROCK)

    private fun isReplaceableCrystalSpace(state: BlockState): Boolean =
        state.isAir || state.`is`(Blocks.FIRE)

    private fun prunePlacedBases(now: Long) {
        val iterator = placedBases.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > 3000L) iterator.remove()
        }
    }

    private fun packetMineModule(): ANPacketMine? {
        if (!ANServiceRegistry.isInitialized) return null
        return ANServiceRegistry.runtime.moduleManager.get("PacketMine") as? ANPacketMine
    }

    private fun clear() {
        currentAttack = null
        currentPlace = null
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class DamageData(val targetDamage: Float, val selfDamage: Float)

    private data class AttackData(
        val crystal: EndCrystal,
        val targetDamage: Float,
        val selfDamage: Float
    )

    private data class PlaceData(
        val basePos: BlockPos,
        val crystalVec: Vec3,
        val targetDamage: Float,
        val selfDamage: Float
    )
}
