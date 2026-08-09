package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.EventPostSync
import anpilot.client.features.event.impl.EventPreSync
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

class ANPhase : ANBaseModule(
    name = "Phase",
    description = "紧贴墙体时自动抛掷末影珍珠穿透墙体",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "珍珠卡墙"
) {
    val factor = addSetting(ANSetting("Factor", 0.5f, 0f, 1f))

    private var clipTimer = 0
    private var targetPos: Vec3? = null

    override fun onEnable() {
        clipTimer = 0
        targetPos = null
    }

    override fun onDisable() {
        targetPos = null
    }

    @Suppress("UNUSED_PARAMETER")
    @ANEventHandler
    fun onPreSync(event: EventPreSync) {
        val player = mc.player ?: return
        if (mc.level == null) return
        if (clipTimer > 0) clipTimer--

        if (!player.onGround()) return
        if (!player.horizontalCollision || playerInsideBlock() || clipTimer > 0 || player.tickCount <= 60) return
        if (mc.options.keyShift.isDown) return

        val target = getTargetPos()
        val pearlSlot = findPearlHotbarSlot()
        if (pearlSlot == Inventory.INVALID_SLOT) return

        targetPos = target
        rotateForPearl(event, target)
    }

    @Suppress("UNUSED_PARAMETER")
    @ANEventHandler
    fun onPostSync(event: EventPostSync) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val connection = mc.connection ?: return

        if (!hasEnderPearl()) {
            disable()
            return
        }

        if (targetPos == null) return
        if (!player.onGround()) return
        if (!player.horizontalCollision || playerInsideBlock() || clipTimer > 0) return
        if (mc.options.keyShift.isDown) return

        val pearlSlot = findPearlHotbarSlot()
        if (pearlSlot == Inventory.INVALID_SLOT) return

        val previousSlot = player.inventory.selected
        Inventory.switchTo(pearlSlot)
        gameMode.useItem(player, InteractionHand.MAIN_HAND)
        connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        Inventory.switchTo(previousSlot)

        clipTimer = 20
        targetPos = null
        disable()
    }

    private fun getTargetPos(): Vec3 {
        val player = mc.player ?: return Vec3.ZERO
        val centerPos = Vec3.atBottomCenterOf(player.blockPosition())
        val playerPos = player.position()
        val f = factor.value.toDouble()

        var closestPos = centerPos
        var minDistance = Double.MAX_VALUE

        val offsets = intArrayOf(1, -1)
        for (x in offsets) {
            for (z in offsets) {
                val pos = centerPos.add(x * f, 0.0, z * f)
                val distance = pos.distanceTo(playerPos)
                if (distance < minDistance) {
                    minDistance = distance
                    closestPos = pos
                }
            }
        }
        return closestPos
    }

    private fun rotateForPearl(event: EventPreSync, target: Vec3) {
        val player = mc.player ?: return
        val oldRotation = Rotation(player)
        val rotations = RotationUtil.getRotationsTo(player.eyePosition, target)
        val pearlRotation = Rotation(rotations[0], if (player.isSprinting) 90f else 80f)

        pearlRotation.apply(player)
        player.yHeadRot = pearlRotation.yaw

        if (ANServiceRegistry.isInitialized) {
            ANServiceRegistry.runtime.rotationManager.setSilentRotation(pearlRotation)
        }

        val previousPostAction = event.postAction
        event.postAction = Runnable {
            previousPostAction?.run()
            oldRotation.apply(player)
            player.yHeadRot = oldRotation.yaw
        }
    }

    private fun findPearlHotbarSlot(): Int {
        val player = mc.player ?: return Inventory.INVALID_SLOT
        if (player.mainHandItem.`is`(Items.ENDER_PEARL)) {
            return player.inventory.selected
        }

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.ENDER_PEARL)) {
                return slot
            }
        }
        return Inventory.INVALID_SLOT
    }

    private fun hasEnderPearl(): Boolean {
        val player = mc.player ?: return false
        for (slot in 0 until Inventory.MAIN_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.ENDER_PEARL)) {
                return true
            }
        }
        return false
    }

    private fun playerInsideBlock(): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val pos = BlockPos.containing(player.x, player.y + 0.2, player.z)
        return !level.isEmptyBlock(pos)
    }
}
