package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.utility.ANTimer
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.Cow
import net.minecraft.world.entity.animal.MushroomCow
import net.minecraft.world.entity.animal.Sheep
import net.minecraft.world.entity.animal.Pig
import net.minecraft.world.entity.animal.Chicken
import net.minecraft.world.entity.animal.Turtle
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ANAutoFeed : ANBaseModule(
    name = "AutoFeed",
    description = "自动消耗背包食物喂食周围选中的动物以进行繁殖与生长",
    category = ANModuleCategory.MISC,
    chineseName = "自动喂食"
), ANWorldRenderModule {
    val scanRange = addSetting(ANSetting("ScanRange", 10.0f, 1.0f, 40.0f))

    val cows = addSetting(ANSetting("Cows", false))
    val sheep = addSetting(ANSetting("Sheep", false))
    val pigs = addSetting(ANSetting("Pigs", false))
    val chickens = addSetting(ANSetting("Chickens", false))
    val turtles = addSetting(ANSetting("Turtles", false))

    private val timer = ANTimer()

    private val lockedTargets = HashSet<UUID>()
    private val feedableCache = ConcurrentHashMap<UUID, Boolean>()
    private val fedCooldowns = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        lockedTargets.clear()
        feedableCache.clear()
        fedCooldowns.clear()
        timer.setMs(3000L)
        
        val player = mc.player ?: return
        val level = mc.level ?: return
        val scanRangeSq = (scanRange.value * scanRange.value).toDouble()

        for (entity in level.entitiesForRendering()) {
            if (entity is Animal && player.distanceToSqr(entity) <= scanRangeSq && isCorrectAnimal(entity)) {
                lockedTargets.add(entity.uuid)
                feedableCache[entity.uuid] = entity.canFallInLove() && !entity.isInLove
            }
        }
    }

    override fun onDisable() {
        lockedTargets.clear()
        feedableCache.clear()
        fedCooldowns.clear()
    }

    override fun onTick() {
        if (fullNullCheck()) return
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (timer.passedAndResetMs(3000L)) {
            val now = System.currentTimeMillis()
            fedCooldowns.keys.removeIf { uuid ->
                val elapsed = now - (fedCooldowns[uuid] ?: 0L)
                elapsed > 300000L
            }

            for (uuid in lockedTargets) {
                val animal = level.entitiesForRendering().find { it.uuid == uuid }
                if (animal is Animal) {
                    val canBreed = animal.canFallInLove() && !animal.isInLove && !fedCooldowns.containsKey(uuid)
                    feedableCache[uuid] = canBreed
                }
            }
        }

        val breedableNearby = level.entitiesForRendering()
            .filterIsInstance<Animal>()
            .any { animal ->
                lockedTargets.contains(animal.uuid) &&
                        player.distanceToSqr(animal) <= 3.5 * 3.5 &&
                        feedableCache[animal.uuid] == true
            }

        if (!breedableNearby) return

        var heldStack = player.mainHandItem
        var hasFood = level.entitiesForRendering()
            .filterIsInstance<Animal>()
            .any { lockedTargets.contains(it.uuid) && isCorrectAnimal(it) && it.isFood(heldStack) }

        if (!hasFood) {
            val foodSlot = findFoodSlot(player, level)
            if (foodSlot != -1) {
                player.inventory.selected = foodSlot
                mc.connection?.send(ServerboundSetCarriedItemPacket(foodSlot))
                heldStack = player.inventory.getItem(foodSlot)
                hasFood = true
            }
        }

        if (!hasFood) {
            sendClientMessage("未检测到对应食物，已自动关闭自动喂食！")
            disable()
            return
        }

        val now = System.currentTimeMillis()
        for (uuid in lockedTargets) {
            if (feedableCache[uuid] != true) continue

            val target = level.entitiesForRendering().find { it.uuid == uuid }
            if (target !is Animal) continue

            if (player.distanceToSqr(target) > 3.5 * 3.5) continue

            heldStack = player.mainHandItem
            if (heldStack.isEmpty || !target.isFood(heldStack)) break

            val box = target.boundingBox

            fedCooldowns[uuid] = now
            feedableCache[uuid] = false

            try {
                val rotations = RotationUtil.getRotationsTo(player.getEyePosition(1.0f), box.center)
                val rotation = Rotation(rotations[0], rotations[1])
                ANServiceRegistry.runtime.rotationManager.setSilentRotation(rotation)

                val actionResult = mc.gameMode?.interact(player, target, InteractionHand.MAIN_HAND)
                if (actionResult?.consumesAction() == true) {
                    player.swing(InteractionHand.MAIN_HAND)
                }
            } finally {
                ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        val player = mc.player ?: return
        val level = mc.level ?: return

        for (entity in level.entitiesForRendering()) {
            if (entity !is Animal || !lockedTargets.contains(entity.uuid)) continue

            val distanceSq = player.distanceToSqr(entity)
            val canFeed = feedableCache[entity.uuid] ?: (entity.canFallInLove() && !entity.isInLove)
            
            val color = if (canFeed) {
                if (distanceSq <= 3.5 * 3.5) {
                    ANColor(255, 50, 50, 255) 
                } else {
                    ANColor(255, 165, 0, 255) 
                }
            } else {
                ANColor(50, 255, 50, 255) 
            }

            ANRender3DEngine.box(
                context,
                entity.boundingBox.inflate(0.04),
                color,
                color.withAlpha(45)
            )
        }
    }

    private fun findFoodSlot(player: Player, level: ClientLevel): Int {
        for (slot in 0 until 9) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val isFood = level.entitiesForRendering()
                .filterIsInstance<Animal>()
                .any { lockedTargets.contains(it.uuid) && isCorrectAnimal(it) && it.isFood(stack) }
            if (isFood) {
                return slot
            }
        }
        return -1
    }

    private fun isCorrectAnimal(animal: Animal): Boolean {
        return when (animal) {
            is Cow, is MushroomCow -> cows.value
            is Sheep -> sheep.value
            is Pig -> pigs.value
            is Chicken -> chickens.value
            is Turtle -> turtles.value
            else -> false
        }
    }
}
