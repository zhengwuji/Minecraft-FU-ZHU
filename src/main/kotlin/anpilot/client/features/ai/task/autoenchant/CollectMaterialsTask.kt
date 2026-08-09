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
import net.minecraft.world.item.Item
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

class CollectMaterialsTask(agent: ANAgent) : AITask(agent) {
    private val mc = Minecraft.getInstance()
    private val timer = ANTimer()
    private var cooldownMs = 0L
    private var phase = Phase.SELECT_REQUEST
    private var requestIndex = 0
    private var chestIndex = 0
    private var interacted = false
    private var bookRequests = emptyList<BookRequest>()
    private var gearRequests = emptyList<GearRequest>()
    private var group = RequestGroup.GEAR

    private enum class Phase {
        SELECT_REQUEST,
        WALK_TO_CHEST,
        OPEN_CHEST,
        TRANSFER,
        CLOSE_CHEST,
        FINISH
    }

    private enum class RequestGroup {
        GEAR,
        BOOKS
    }

    override fun start() {
        val module = agent.module as? ANAutoEnchant ?: return finish()
        gearRequests = createGearRequests(module)
        bookRequests = module.selectedEnchants().map { BookRequest(it, module.requiredBookCount(it)) }
        group = if (gearRequests.isNotEmpty()) RequestGroup.GEAR else RequestGroup.BOOKS
        phase = Phase.SELECT_REQUEST
        requestIndex = 0
        chestIndex = 0
        interacted = false
        setCooldown(0)
    }

    override fun tick() {
        if (!timer.passedMs(cooldownMs)) return
        val module = agent.module as? ANAutoEnchant ?: return finish()
        val player = player ?: return

        when (phase) {
            Phase.SELECT_REQUEST -> {
                if (group == RequestGroup.GEAR) {
                skipCompletedGearRequests(module)
                if (requestIndex >= gearRequests.size) {
                    group = RequestGroup.BOOKS
                    requestIndex = 0
                    chestIndex = 0
                    return
                }
                if (module.gearChests().isEmpty()) {
                    AgentUtils.sendMessage("CHECK:没有绑定装备/工具箱,无法获取取材料")
                    return finish()
                }
                phase = Phase.WALK_TO_CHEST
                } else {
                skipCompletedBookRequests(module)
                if (requestIndex >= bookRequests.size) {
                    phase = Phase.FINISH
                    return
                }
                val request = bookRequests[requestIndex]
                if (module.bookChestsFor(request.spec).isEmpty()) {
                    player.closeContainer()
                    module.disable("没有绑定 ${request.spec.label} 附魔书箱")
                    return finish()
                }
                phase = Phase.WALK_TO_CHEST
                }
            }
            Phase.WALK_TO_CHEST -> {
                val chest = currentChest(module) ?: return finish()
                val distance = player.eyePosition.distanceTo(Vec3.atCenterOf(chest.pos))
                if (distance <= 3.0) {
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
                val chest = currentChest(module) ?: return finish()
                if (!interacted) {
                    interactBlock(chest.pos)
                    interacted = true
                    setCooldown(250)
                } else {
                    setCooldown(250)
                }
            }
            Phase.TRANSFER -> {
                val menu = currentStorageMenu()
                if (menu == null) {
                    phase = Phase.OPEN_CHEST
                    interacted = false
                    return
                }
                val storageSize = storageSlotCount(menu)
                val slot = when (group) {
                    RequestGroup.GEAR -> findGearSlot(menu, storageSize, gearRequests[requestIndex].items)
                    else -> findBookSlot(menu, storageSize, module, bookRequests[requestIndex].spec)
                }

                if (slot == -1) {
                    if (moveToNextChest(module)) return
                    val label = currentRequestLabel()
                    if (group == RequestGroup.BOOKS) {
                        player.closeContainer()
                        module.disable("绑定箱子内没有需要的附魔书: $label")
                        return finish()
                    }
                    AgentUtils.sendMessage("CHECK绑定箱子内缺少材料: $label")
                    phase = Phase.CLOSE_CHEST
                    return
                }

                mc.gameMode?.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player)
                setCooldown(200)

                if (isCurrentRequestComplete(module)) {
                    phase = Phase.CLOSE_CHEST
                }
            }
            Phase.CLOSE_CHEST -> {
                player.closeContainer()
                requestIndex++
                chestIndex = 0
                interacted = false
                phase = Phase.SELECT_REQUEST
                setCooldown(200)
            }
            Phase.FINISH -> {
                agent.scheduler.push(BootTask(agent))
                finish()
            }
        }
    }

    override fun stop() {
        BaritoneHelper.cancel()
        player?.closeContainer()
    }

    private fun currentChest(module: ANAutoEnchant): ANAutoEnchant.ChestBinding? {
        val chests = when (group) {
            RequestGroup.GEAR -> module.gearChests()
            else -> module.bookChestsFor(bookRequests.getOrNull(requestIndex)?.spec ?: return null)
        }
        return chests.getOrNull(chestIndex)
    }

    private fun moveToNextChest(module: ANAutoEnchant): Boolean {
        val chests = when (group) {
            RequestGroup.GEAR -> module.gearChests()
            else -> module.bookChestsFor(bookRequests.getOrNull(requestIndex)?.spec ?: return false)
        }
        if (chestIndex + 1 >= chests.size) return false
        player?.closeContainer()
        chestIndex++
        interacted = false
        phase = Phase.WALK_TO_CHEST
        setCooldown(200)
        return true
    }

    private fun skipCompletedGearRequests(module: ANAutoEnchant) {
        while (requestIndex < gearRequests.size && inventoryItemCount(gearRequests[requestIndex].items) >= gearRequests[requestIndex].count) {
            requestIndex++
        }
    }

    private fun skipCompletedBookRequests(module: ANAutoEnchant) {
        while (requestIndex < bookRequests.size && inventoryBookCount(module, bookRequests[requestIndex].spec) >= bookRequests[requestIndex].count) {
            requestIndex++
        }
    }

    private fun isCurrentRequestComplete(module: ANAutoEnchant): Boolean {
        return when (group) {
            RequestGroup.GEAR -> inventoryItemCount(gearRequests[requestIndex].items) >= gearRequests[requestIndex].count
            else -> inventoryBookCount(module, bookRequests[requestIndex].spec) >= bookRequests[requestIndex].count
        }
    }

    private fun currentRequestLabel(): String {
        return when (group) {
            RequestGroup.GEAR -> gearRequests.getOrNull(requestIndex)?.label ?: "装备/工具"
            else -> bookRequests.getOrNull(requestIndex)?.spec?.label ?: "附魔书"
        }
    }

    private fun findGearSlot(menu: AbstractContainerMenu, storageSize: Int, items: List<Item>): Int {
        for (slot in 0 until storageSize) {
            val stack = menu.slots[slot].item
            if (!stack.isEmpty && items.contains(stack.item)) return slot
        }
        return -1
    }

    private fun findBookSlot(menu: AbstractContainerMenu, storageSize: Int, module: ANAutoEnchant, spec: EnchantSpec): Int {
        for (slot in 0 until storageSize) {
            val stack = menu.slots[slot].item
            if (module.isMatchingBook(stack, spec)) return slot
        }
        return -1
    }

    private fun inventoryItemCount(items: List<Item>): Int {
        val player = player ?: return 0
        var count = 0
        for (slot in 0 until 36) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && items.contains(stack.item)) count++
        }
        return count
    }

    private fun inventoryBookCount(module: ANAutoEnchant, spec: EnchantSpec): Int {
        val player = player ?: return 0
        var count = 0
        for (slot in 0 until 36) {
            val stack = player.inventory.getItem(slot)
            if (module.isMatchingBook(stack, spec)) count++
        }
        return count
    }

    private fun createGearRequests(module: ANAutoEnchant): List<GearRequest> = module.gearRequests()

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
