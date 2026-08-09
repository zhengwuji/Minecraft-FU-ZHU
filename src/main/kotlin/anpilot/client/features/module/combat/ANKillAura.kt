package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.event.impl.PlayerUpdateEvent
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.manager.ANFriendManager
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationPriority
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.utility.ANTimer
import anpilot.client.minecraft.mixin.accessor.ANMultiPlayerGameModeAccessor
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.monster.Shulker
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.util.Mth
import java.awt.Color
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.hypot

class ANKillAura : ANBaseModule(
    name = "KillAura",
    description = "自动攻击周围攻击范围内的目标实体",
    category = ANModuleCategory.COMBAT,
    chineseName = "杀戮光环"
), ANWorldRenderModule {
    val page = addSetting(ANSetting("Page", Page.GENERAL))

    val range = addSetting(ANSetting("Range", 4.0f, 0.5f, 6.0f) { isPage(Page.GENERAL) })
    val searchRange = addSetting(ANSetting("SearchRange", 6.0f, 0.5f, 12.0f) { isPage(Page.GENERAL) })
    val hitDelayMs = addSetting(ANSetting("HitDelayMs", 500, 0, 2000) { isPage(Page.GENERAL) })
    val multitask = addSetting(ANSetting("Multitask", true) { isPage(Page.GENERAL) })
    val awaitCrits = addSetting(ANSetting("AwaitCrits", false) { isPage(Page.GENERAL) })
    val swing = addSetting(ANSetting("Swing", true) { isPage(Page.GENERAL) })
    val rotate = addSetting(ANSetting("Rotate", RotateMode.SILENT) { isPage(Page.GENERAL) })
    val rayTrace = addSetting(ANSetting("RayTrace", RayTraceMode.ONLY_TARGET) { isPage(Page.GENERAL) })
    val minYawStep = addSetting(ANSetting("MinYawStep", 65, 1, 180) {
        false
    })
    val maxYawStep = addSetting(ANSetting("MaxYawStep", 75, 1, 180) {
        false
    })
    val aimedPitchStep = addSetting(ANSetting("AimedPitchStep", 1.0f, 0.0f, 90.0f) {
        false
    })
    val maxPitchStep = addSetting(ANSetting("MaxPitchStep", 8.0f, 1.0f, 90.0f) {
        false
    })
    val pitchAccelerate = addSetting(ANSetting("PitchAccelerate", 1.65f, 1.0f, 10.0f) {
        false
    })

    val targetPlayers = addSetting(ANSetting("Players", true) { isPage(Page.TARGET) })
    val targetHostiles = addSetting(ANSetting("Hostiles", true) { isPage(Page.TARGET) })
    val targetAngry = addSetting(ANSetting("OnlyAngry", true) { isPage(Page.TARGET) && targetHostiles.value })
    val targetAnimals = addSetting(ANSetting("Animals", false) { isPage(Page.TARGET) })

    val targetRender = addSetting(ANSetting("TargetRender", TargetRenderMode.BOX) { isPage(Page.TARGET) })
    val targetColor = addSetting(ANSetting("TargetColor", ColorGroupSetting(Color(0xCCFF3333.toInt(), true).rgb)) {
        isPage(Page.TARGET) && targetRender.value == TargetRenderMode.BOX
    })

    val requireWeapon = addSetting(ANSetting("Weapon", WeaponMode.SWORD) { isPage(Page.WEAPON) })
    val autoSwap = addSetting(ANSetting("AutoSwap", true) { isPage(Page.WEAPON) })
    val silentSwap = addSetting(ANSetting("SilentSwap", false) { isPage(Page.WEAPON) && autoSwap.value })
    val swapBack = addSetting(ANSetting("SwapBack", true) { isPage(Page.WEAPON) && autoSwap.value && silentSwap.value })

    private var running = false
    private var auraTarget: Entity? = null
    private val attackDelayTimer = ANTimer()
    private var attackTarget: Entity? = null
    private var attackWeapon: WeaponSlot? = null
    private var attackRotation: Rotation? = null
    private var rotationInitialized = false
    private var rotationYaw = 0.0f
    private var rotationPitch = 0.0f
    private var pitchAcceleration = 1.0f
    private var lookingAtHitbox = false
    private var rotationPoint = Vec3.ZERO
    private var rotationMotion = Vec3.ZERO
    private var firstHitCompletedTargetId: Int? = null

    enum class RotateMode {
        OFF,
        SILENT,
        GRIM
    }

    enum class WeaponMode {
        SWORD,
        AXE
    }

    enum class Page {
        GENERAL,
        WEAPON,
        TARGET
    }

    enum class TargetRenderMode {
        BOX
    }

    enum class RayTraceMode {
        OFF,
        ONLY_TARGET,
        ALL_ENTITIES
    }

    private fun isPage(target: Page): Boolean = page.value == target

    override fun onDisable() {
        auraTarget = null
        running = false
        attackTarget = null
        attackWeapon = null
        attackRotation = null
        rotationInitialized = false
        lookingAtHitbox = false
        rotationPoint = Vec3.ZERO
        rotationMotion = Vec3.ZERO
        firstHitCompletedTargetId = null
        Inventory.endSwap()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        disable()
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onUpdatePre(event: PlayerUpdateEvent.Pre) {
        running = false
        attackTarget = null
        attackWeapon = null
        attackRotation = null

        val player = mc.player ?: return
        if (mc.level == null || player.isSpectator) return
        if (player.isUsingItem && !multitask.value) return
        if (autoCrystalRunning()) return

        val target = getAuraTarget()
        auraTarget = target
        if (target == null) {
            firstHitCompletedTargetId = null
            return
        }

        val weapon = getAuraWeapon()
        if (!autoSwap.value && !matchesWeapon(player.mainHandItem, requireWeapon.value)) return

        val attackStack = if (weapon.found) weapon.stack else player.mainHandItem
        if (!canAttack(attackStack)) return

        if (rotate.value != RotateMode.OFF) {
            val rotation = calculateAttackRotation(target)
            attackRotation = rotation

            if (rotate.value != RotateMode.GRIM) {
                ANServiceRegistry.runtime.rotationManager.requestRotation(
                    rotation = rotation,
                    priority = RotationPriority.COMBAT,
                    owner = "KillAuraPlus",
                    yawStep = 360.0f,
                    pitchStep = 180.0f
                )
            }
            
        } else {
            resetAttackRotation(player)
        }

        attackTarget = target
        attackWeapon = weapon
    }

    @ANEventHandler
    fun onPlayerUpdatePost(event: PlayerUpdateEvent.Post) {
        val target = attackTarget ?: return
        val weapon = attackWeapon ?: return
        val rotation = attackRotation
        val player = mc.player ?: return
        val delayFirstHit = shouldDelayFirstHitForReadyGate(player, target, rotation)

        if (rotation != null && (rotate.value == RotateMode.GRIM || !ANServiceRegistry.runtime.rotationManager.isRotationReached(rotation))) {
            ANServiceRegistry.runtime.rotationManager.sendInstantRotation(rotation)
        }

        if (delayFirstHit) {
            attackTarget = null
            attackWeapon = null
            attackRotation = null
            return
        }

        runAttack(target, weapon, rotation)

        if (rotation != null && rotate.value == RotateMode.GRIM) {
            mc.player?.let {
                ANServiceRegistry.runtime.rotationManager.sendInstantRotation(
                    Rotation(it.yRot, it.xRot),
                    mouseSensitivityFix = false
                )
            }
        }

        attackTarget = null
        attackWeapon = null
        attackRotation = null
    }

    override fun renderWorld(context: LevelRenderContext) {
        val target = auraTarget
        if (target == null || !target.isAlive) {
            auraTarget = null
            return
        }

        if (targetRender.value != TargetRenderMode.BOX) return

        val color = targetColor.value.toANColor()
        ANRender3DEngine.box(
            context,
            target.boundingBox.inflate(0.04),
            color.withAlpha(255),
            color.withAlpha(45)
        )
    }

    private fun calculateAttackRotation(target: Entity): Rotation {
        val player = mc.player ?: return Rotation(rotationYaw, rotationPitch)

        if (!rotationInitialized) {
            resetAttackRotation(player)
        }

        val targetVec = getAttackLookPoint(target)
        val targetYaw = Mth.wrapDegrees(
            Math.toDegrees(Math.atan2(targetVec.z - player.z, targetVec.x - player.x)).toFloat() - 90.0f
        )
        val targetPitch = -Math.toDegrees(
            Math.atan2(
                targetVec.y - (player.y + player.eyeHeight),
                hypot(targetVec.x - player.x, targetVec.z - player.z)
            )
        ).toFloat()

        val deltaYaw = Mth.wrapDegrees(targetYaw - rotationYaw)
        val deltaPitch = targetPitch - rotationPitch

        pitchAcceleration = if (lookingAtHitbox) {
            aimedPitchStep.value
        } else {
            Mth.clamp(pitchAcceleration * pitchAccelerate.value, aimedPitchStep.value, maxPitchStep.value)
        }

        val yawStep = 360.0f
        val pitchStep = 180.0f

        val steppedYaw = rotationYaw + Mth.clamp(deltaYaw, -yawStep, yawStep)
        val steppedPitch = Mth.clamp(rotationPitch + Mth.clamp(deltaPitch, -pitchStep, pitchStep), -90.0f, 90.0f)

        rotationYaw = steppedYaw
        rotationPitch = steppedPitch

        val rotation = Rotation(rotationYaw, rotationPitch).wrap()
        lookingAtHitbox = rayTraceEntity(player, rotation, range.value.toDouble())?.entity === target
        return rotation
    }

    private fun resetAttackRotation(player: Player) {
        rotationYaw = player.yRot
        rotationPitch = player.xRot
        pitchAcceleration = aimedPitchStep.value
        lookingAtHitbox = false
        rotationInitialized = true
    }

    private fun getAttackLookPoint(target: Entity): Vec3 {
        val player = mc.player ?: return target.eyePosition
        val box = target.boundingBox
        val lengthX = box.xsize
        val lengthY = box.ysize
        val lengthZ = box.zsize

        if (rotationMotion == Vec3.ZERO) {
            rotationMotion = Vec3(
                randomStep(-0.05f, 0.05f).toDouble(),
                randomStep(0.001f, 0.03f).toDouble(),
                randomStep(-0.05f, 0.05f).toDouble()
            )
            rotationPoint = Vec3(0.0, lengthY * 0.5, 0.0)
        }

        rotationPoint = rotationPoint.add(rotationMotion)
        val halfX = (lengthX.toDouble() - 0.05) * 0.5
        val halfZ = (lengthZ.toDouble() - 0.05) * 0.5

        if (rotationPoint.x >= halfX) {
            rotationMotion = Vec3(-randomStep(0.003f, 0.03f).toDouble(), rotationMotion.y, rotationMotion.z)
        }
        if (rotationPoint.x <= -halfX) {
            rotationMotion = Vec3(randomStep(0.003f, 0.03f).toDouble(), rotationMotion.y, rotationMotion.z)
        }
        if (rotationPoint.y >= lengthY) {
            rotationMotion = Vec3(rotationMotion.x, -randomStep(0.001f, 0.03f).toDouble(), rotationMotion.z)
        }
        if (rotationPoint.y <= 0.05) {
            rotationMotion = Vec3(rotationMotion.x, randomStep(0.001f, 0.03f).toDouble(), rotationMotion.z)
        }
        if (rotationPoint.z >= halfZ) {
            rotationMotion = Vec3(rotationMotion.x, rotationMotion.y, -randomStep(0.003f, 0.03f).toDouble())
        }
        if (rotationPoint.z <= -halfZ) {
            rotationMotion = Vec3(rotationMotion.x, rotationMotion.y, randomStep(0.003f, 0.03f).toDouble())
        }

        val trackedPoint = Vec3(
            Mth.clamp(rotationPoint.x, -halfX, halfX),
            Mth.clamp(rotationPoint.y, 0.05, lengthY),
            Mth.clamp(rotationPoint.z, -halfZ, halfZ)
        )
        val candidate = Vec3(target.x + trackedPoint.x, target.y + trackedPoint.y, target.z + trackedPoint.z)
        if (targetDistanceSqr(player, target) <= range.value * range.value && canSeePoint(player, target, candidate)) {
            return candidate
        }

        return findVisiblePoint(player, target) ?: target.eyePosition
    }

    private fun findVisiblePoint(player: Player, target: Entity): Vec3? {
        val box = target.boundingBox
        val halfX = box.xsize * 0.5
        val halfZ = box.zsize * 0.5
        var x = -halfX
        while (x <= halfX) {
            var z = -halfZ
            while (z <= halfZ) {
                var y = 0.05
                while (y <= box.ysize) {
                    val point = Vec3(target.x + x, target.y + y, target.z + z)
                    if (player.eyePosition.distanceToSqr(point) <= range.value * range.value && canSeePoint(player, target, point)) {
                        return point
                    }
                    y += 0.25
                }
                z += 0.15
            }
            x += 0.15
        }
        return null
    }

    private fun canSeePoint(player: Player, target: Entity, point: Vec3): Boolean {
        val rotations = RotationUtil.getRotationsTo(player.eyePosition, point)
        return rayTraceEntity(player, Rotation(rotations[0], rotations[1]), range.value.toDouble())?.entity === target
    }

    private fun randomStep(min: Float, max: Float): Float {
        if (abs(max - min) < 0.001f) return min
        val lower = min.coerceAtMost(max)
        val upper = min.coerceAtLeast(max)
        return ThreadLocalRandom.current().nextDouble(lower.toDouble(), upper.toDouble()).toFloat()
    }

    private fun runAttack(target: Entity, weapon: WeaponSlot, rotation: Rotation?) {
        val player = mc.player ?: return
        if (!canHitTarget(player, target)) return

        val swapped = when {
            weapon.slot == Inventory.INVALID_SLOT || weapon.slot == player.inventory.selected -> true
            silentSwap.value -> Inventory.startSwap(weapon.slot)
            autoSwap.value -> Inventory.switchTo(weapon.slot)
            else -> true
        }

        if (!swapped) return

        running = true
        try {
            val attackTarget = resolveRayTraceTarget(player, target, rotation) ?: return
            if (attackEntity(attackTarget)) {
                firstHitCompletedTargetId = attackTarget.id
                attackDelayTimer.reset()
            }
        } finally {
            if (silentSwap.value && swapBack.value) {
                Inventory.endSwap()
            }
        }
    }

    private fun shouldDelayFirstHitForReadyGate(player: Player, target: Entity, rotation: Rotation?): Boolean {
        if (firstHitCompletedTargetId == target.id) return false
        if (rayTrace.value == RayTraceMode.OFF) return false

        val serverRotation = ANServiceRegistry.runtime.rotationManager.serverRotation
        if (resolveRayTraceTarget(player, target, serverRotation) != null) return false

        if (rotate.value == RotateMode.GRIM && rotation != null &&
            resolveRayTraceTarget(player, target, rotation) != null
        ) {
            return false
        }

        return true
    }

    private fun canAttack(weaponStack: ItemStack): Boolean {
        val player = mc.player ?: return false
        if (!attackDelayTimer.passedMs(hitDelayMs.value.toLong())) return false
        return !awaitCrits.value || player.onGround() || player.deltaMovement.y < 0.0
    }

    private fun attackEntity(entity: Entity): Boolean {
        val player = mc.player ?: return false
        val connection = mc.connection ?: return false
        val sprinting = player.isSprinting && rotate.value != RotateMode.GRIM

        if (sprinting) {
            connection.send(
                ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
                )
            )
        }

        (mc.gameMode as? ANMultiPlayerGameModeAccessor)?.`anpilot$ensureHasSentCarriedItem`()

        
        connection.send(ServerboundInteractPacket.createAttackPacket(entity, player.isShiftKeyDown))

        
        if (swing.value) {
            player.swing(InteractionHand.MAIN_HAND)
        } else {
            connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        }

        player.resetAttackStrengthTicker()

        if (sprinting) {
            connection.send(
                ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.START_SPRINTING
                )
            )
        }

        return true
    }

    private fun canHitTarget(player: Player, entity: Entity): Boolean {
        return entity !== player &&
                entity.isAlive &&
                isValidTarget(entity) &&
                targetDistanceSqr(player, entity) <= range.value * range.value
    }

    private fun resolveRayTraceTarget(player: Player, target: Entity, rotation: Rotation?): Entity? {
        if (rayTrace.value == RayTraceMode.OFF) return target

        val hit = rayTraceEntity(player, rotation ?: Rotation(player.yRot, player.xRot), range.value.toDouble()) ?: return null
        val hitEntity = hit.entity
        return when (rayTrace.value) {
            RayTraceMode.OFF -> target
            RayTraceMode.ONLY_TARGET -> target.takeIf { hitEntity === target }
            RayTraceMode.ALL_ENTITIES -> hitEntity.takeIf { canHitTarget(player, it) }
        }
    }

    private fun rayTraceEntity(player: Player, rotation: Rotation, traceRange: Double): EntityHitResult? {
        val level = mc.level ?: return null
        val start = player.eyePosition
        val direction = Vec3.directionFromRotation(rotation.pitch, rotation.yaw)
        val end = start.add(direction.x * traceRange, direction.y * traceRange, direction.z * traceRange)
        val searchBox = player.boundingBox.expandTowards(direction.scale(traceRange)).inflate(1.0, 1.0, 1.0)

        val entityHit = ProjectileUtil.getEntityHitResult(
            player,
            start,
            end,
            searchBox,
            EntitySelector.NO_SPECTATORS.and { entity -> entity !== player },
            traceRange * traceRange
        ) ?: return null

        val blockHit = level.clip(
            ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
            )
        )
        if (blockHit.type != HitResult.Type.MISS &&
            start.distanceToSqr(blockHit.location) < start.distanceToSqr(entityHit.location)
        ) {
            return null
        }

        return entityHit
    }

    private fun getAuraTarget(): Entity? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val maxDistance = searchRange.value * searchRange.value

        val box = player.boundingBox.inflate(searchRange.value.toDouble())
        return level.getEntities(player, box)
            .asSequence()
            .filter { it.isAlive && isValidTarget(it) }
            .filter { targetDistanceSqr(player, it) <= maxDistance }
            .minByOrNull { targetDistanceSqr(player, it) }
    }

    private fun targetDistanceSqr(player: Player, entity: Entity): Double {
        return entity.boundingBox.distanceToSqr(player.eyePosition)
    }

    private fun isValidTarget(entity: Entity): Boolean {
        if (isValidProjectile(entity)) return true
        if (entity !is LivingEntity) return false
        if (entity is Player) {
            if (!targetPlayers.value || entity.isSpectator || entity.isCreative) return false
            return !ANFriendManager.isFriend(entity.name.string)
        }

        if (targetHostiles.value && entity is Shulker) return true
        if (targetHostiles.value && (entity is Monster || entity is Enemy)) return isHostileTarget(entity)
        if (targetAnimals.value && entity is Animal) return true
        return false
    }

    private fun isHostileTarget(entity: LivingEntity): Boolean {
        if (!targetAngry.value) return true
        val player = mc.player ?: return false
        if (entity is NeutralMob && entity.isAngry) {
            val target = entity.target
            return target == null || target === player
        }
        val mob = entity as? Mob ?: return false
        return mob.target === player || mob.lastHurtByMob === player || mob.isAggressive
    }


    private fun isValidProjectile(entity: Entity): Boolean {
        val projectile = entity as? Projectile ?: return false
        if (!projectile.isPickable || !projectile.isAttackable) return false
        val owner = projectile.owner
        val player = mc.player
        if (owner === player) return false
        if (owner is Player && ANFriendManager.isFriend(owner.name.string)) return false
        return true
    }

    private fun getAuraWeapon(): WeaponSlot {
        val selectedWeapon = requireWeapon.value
        return findBestHotbarWeapon { stack -> matchesSelectedWeapon(stack, selectedWeapon) }
            .takeIf { it.found }
            ?: WeaponSlot.notFound()
    }

    private fun findBestHotbarWeapon(predicate: (ItemStack) -> Boolean): WeaponSlot {
        val player = mc.player ?: return WeaponSlot.notFound()
        var best = WeaponSlot.notFound()
        var bestScore = Float.NEGATIVE_INFINITY

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !predicate(stack)) continue

            val score = weaponScore(stack)
            if (score > bestScore) {
                bestScore = score
                best = WeaponSlot(slot, stack)
            }
        }

        return best
    }

    private fun matchesSelectedWeapon(stack: ItemStack, selectedWeapon: WeaponMode): Boolean {
        return matchesWeapon(stack, selectedWeapon)
    }

    private fun matchesWeapon(stack: ItemStack, selectedWeapon: WeaponMode): Boolean {
        if (stack.isEmpty) return false
        return when (selectedWeapon) {
            WeaponMode.SWORD -> stack.`is`(ItemTags.SWORDS)
            WeaponMode.AXE -> stack.`is`(ItemTags.AXES)
        }
    }

    private fun weaponScore(stack: ItemStack): Float {
        val material = Inventory.materialRank(stack).toFloat()
        val sharpness = Inventory.getEnchantmentLevel(stack, Enchantments.SHARPNESS).toFloat()
        return when {
            stack.`is`(ItemTags.SWORDS) -> 700.0f + material + sharpness
            stack.`is`(ItemTags.AXES) -> 600.0f + material + sharpness
            else -> 0.0f
        }
    }

    private fun getAttackSpeed(stack: ItemStack): Double {
        val player = mc.player ?: return 4.0
        var speed = player.getAttributeBaseValue(Attributes.ATTACK_SPEED)
        val modifiers = stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED)
        for (modifier in modifiers) {
            speed += modifier.amount
        }
        return speed
    }


    private fun autoCrystalRunning(): Boolean {
        if (!ANServiceRegistry.isInitialized) return false
        return (ANServiceRegistry.runtime.moduleManager.get("AutoCrystal") as? ANAutoCrystal)?.isRunning() == true
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class WeaponSlot(val slot: Int, val stack: ItemStack) {
        val found: Boolean
            get() = slot != Inventory.INVALID_SLOT && !stack.isEmpty

        companion object {
            fun notFound(): WeaponSlot = WeaponSlot(Inventory.INVALID_SLOT, ItemStack.EMPTY)
        }
    }
}
