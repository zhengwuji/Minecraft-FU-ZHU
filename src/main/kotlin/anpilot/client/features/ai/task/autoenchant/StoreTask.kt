package anpilot.client.features.ai.task.autoenchant

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.player.ANAutoEnchant
import anpilot.client.features.utility.ANTimer
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.ShulkerBoxMenu
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

class StoreTask(agent: ANAgent) : AITask(agent) {
    private val mc = Minecraft.getInstance()
    private val timer = ANTimer()
    private var cooldownMs = 0L
    private var phase = Phase.WALK_TO_CHEST
    private var chestIndex = 0
    private var interacted = false

    private enum class Phase {
        WALK_TO_CHEST,
        OPEN_CHEST,
        TRANSFER,
        FINISH
    }

    override fun start() {
        phase = Phase.WALK_TO_CHEST
        chestIndex = 0
        interacted = false
        setCooldown(0)
    }

    override fun tick() {
        if (!timer.passedMs(cooldownMs)) return
        val module = agent.module as? ANAutoEnchant ?: return finish()
        val player = player ?: return
        val chest = module.outputChests().getOrNull(chestIndex) ?: return finishWithMessage(module, "CHECK:没有可用输出箱")

        when (phase) {
            Phase.WALK_TO_CHEST -> {
                if (player.eyePosition.distanceTo(Vec3.atCenterOf(chest.pos)) <= 3.0) {
                    BaritoneHelper.cancel()
                    phase = Phase.OPEN_CHEST
                    interacted = false
                } else {
                    BaritoneHelper.pathNear(chest.pos, 1)
                    setCooldown(150)
                }
            }
            Phase.OPEN_CHEST -> {
                if (currentStorageMenu() != null) {
                    phase = Phase.TRANSFER
                    return
                }
                if (!interacted) {
                    interactBlock(chest.pos)
                    interacted = true
                    setCooldown(250)
                } else {
                    setCooldown(250)
                }
            }
            Phase.TRANSFER -> {
                val menu = currentStorageMenu() ?: run {
                    phase = Phase.OPEN_CHEST
                    interacted = false
                    return
                }
                val slot = findTargetItemSlot(menu, module)
                if (slot == -1) {
                    player.closeContainer()
                    phase = Phase.FINISH
                    setCooldown(200)
                    return
                }
                mc.gameMode?.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player)
                setCooldown(150)
            }
            Phase.FINISH -> {
                module.sendClientMessage("CHECK:存入输出箱")
                agent.scheduler.push(BootTask(agent))
                finish()
            }
        }
    }

    override fun stop() {
        BaritoneHelper.cancel()
        player?.closeContainer()
    }

    private fun findTargetItemSlot(menu: AbstractContainerMenu, module: ANAutoEnchant): Int {
        val storageSize = storageSlotCount(menu)
        for (slot in storageSize until menu.slots.size) {
            val stack = menu.slots[slot].item
            if (module.isTargetItem(stack)) return slot
        }
        return -1
    }

    private fun currentStorageMenu(): AbstractContainerMenu? {
        val menu = player?.containerMenu ?: return null
        return if (menu is ChestMenu || menu is ShulkerBoxMenu) menu else null
    }

    private fun storageSlotCount(menu: AbstractContainerMenu): Int =
        (menu.slots.size - PLAYER_INVENTORY_MENU_SLOTS).coerceAtLeast(0)

    private fun interactBlock(pos: BlockPos) {
        val p = player ?: return
        val targetVec = Vec3.atCenterOf(pos)
        val rotations = RotationUtil.getRotationsTo(p.eyePosition, targetVec)
        p.yRot = rotations[0]
        p.xRot = rotations[1]
        val hit = BlockHitResult(targetVec, Direction.UP, pos, false)
        mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, hit)
        p.swing(InteractionHand.MAIN_HAND)
    }

    private fun finishWithMessage(module: ANAutoEnchant, message: String) {
        AgentUtils.sendMessage(message)
        finish()
    }

    private fun setCooldown(ms: Long) {
        cooldownMs = ms
        timer.reset()
    }

    private fun finish() {
        finished = true
    }

    private companion object {
        const val PLAYER_INVENTORY_MENU_SLOTS = 36
    }
}
