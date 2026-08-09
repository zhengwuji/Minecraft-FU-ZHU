package anpilot.client.features.ai.task.autobuild

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.module.misc.ANAutoBuild
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.utility.ANTimer
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.ShulkerBoxMenu
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.client.player.LocalPlayer


class MaterialRefillTask(
    agent: ANAgent,
    val chestPos: BlockPos,
    val materialBlock: Block,
    chestPositions: List<BlockPos> = listOf(chestPos),
    val refillType: RefillType = RefillType.LOW
) : AITask(agent) {

    private val mc = Minecraft.getInstance()
    private val refillChests = chestPositions.distinct().ifEmpty { listOf(chestPos) }
    private var chestIndex = 0
    private var currentChestPos = refillChests.first()
    private var phase = Phase.WALK_TO_CHEST
    private val actionTimer = ANTimer()
    private val phaseTimer = ANTimer()
    private var cooldownMs = 0L
    private var interacted = false
    private var beforeTransferCount = 0
    private var lastObservedCount = -1
    private var stableCountChecks = 0
    private var failedTransferAttempts = 0
    private var outOfMaterial = false
    private var lastDiscardSlot = -1
    private var discardAttempts = 0

    private enum class Phase {
        WALK_TO_CHEST,
        OPEN_CHEST,
        TRANSFER_ITEMS,
        VERIFY_TRANSFER,
        CLOSE_CHEST,
        FINISH
    }

    private fun setCooldown(ms: Long) {
        cooldownMs = ms
        actionTimer.reset()
    }

    private fun moveTo(next: Phase, cooldown: Long = 0L) {
        phase = next
        phaseTimer.reset()
        setCooldown(cooldown)
    }

    override fun start() {
        interacted = false
        chestIndex = refillChests.indexOf(chestPos).takeIf { it >= 0 } ?: 0
        currentChestPos = refillChests[chestIndex]
        phase = Phase.WALK_TO_CHEST
        phaseTimer.reset()
        beforeTransferCount = 0
        lastObservedCount = -1
        stableCountChecks = 0
        failedTransferAttempts = 0
        outOfMaterial = false
        setCooldown(0)
    }

    override fun tick() {
        if (!actionTimer.passedMs(cooldownMs)) return

        val p = player ?: return

        when (phase) {
            Phase.WALK_TO_CHEST -> {
                val distance = p.eyePosition.distanceTo(Vec3.atCenterOf(currentChestPos))
                if (distance <= 2.0) {
                    BaritoneHelper.cancel()
                    moveTo(Phase.OPEN_CHEST)
                } else {
                    BaritoneHelper.pathNear(currentChestPos, 1)
                    setCooldown(100)
                }
            }
            Phase.OPEN_CHEST -> {
                val handler = p.containerMenu
                if (handler is ChestMenu || handler is ShulkerBoxMenu) {
                    moveTo(Phase.TRANSFER_ITEMS)
                    return
                }

                if (!interacted) {
                    val targetVec = Vec3.atCenterOf(currentChestPos)
                    val rotations = RotationUtil.getRotationsTo(p.eyePosition, targetVec)
                    p.yRot = rotations[0]
                    p.xRot = rotations[1]

                    val hit = BlockHitResult(targetVec, Direction.UP, currentChestPos, false)
                    mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, hit)
                    p.swing(InteractionHand.MAIN_HAND)
                    interacted = true
                    setCooldown(200)
                } else {
                    if (phaseTimer.passedMs(3000L)) {
                        finished = true
                    } else {
                        setCooldown(250)
                    }
                }
            }
            Phase.TRANSFER_ITEMS -> {
                val handler = currentStorageMenu()
                if (handler == null) {
                    interacted = false
                    moveTo(Phase.OPEN_CHEST)
                    return
                }

                val module = agent.module as? ANAutoBuild
                module?.updateChestBindingsFromOpenContainer(handler, currentChestPos)

                val storageSize = storageSlotCount(handler)

                var isChestEmpty = true
                for (slot in 0 until storageSize) {
                    if (!handler.slots[slot].item.isEmpty) {
                        isChestEmpty = false
                        break
                    }
                }
                if (isChestEmpty && !phaseTimer.passedMs(500L)) {
                    setCooldown(50)
                    return
                }

                if (module != null) {
                    var emptySlotsCount = 0
                    for (slot in storageSize until storageSize + 36) {
                        if (handler.slots[slot].item.isEmpty) {
                            emptySlotsCount++
                        }
                    }

                    if (emptySlotsCount < 2) {
                        module.sendClientMessage("CHECK校验清理背包转移")
                        var slotToFree = -1
                        
                        for (slot in storageSize until storageSize + 36) {
                            val stack = handler.slots[slot].item
                            if (!stack.isEmpty && stack.item is BlockItem) {
                                val block = (stack.item as BlockItem).block
                                val canonical = module.getCanonicalBlock(block)
                                if (!module.isBlockNeeded(canonical)) {
                                    slotToFree = slot
                                    break
                                }
                            }
                        }
                        if (slotToFree == -1) {
                            val targetCanonical = module.getCanonicalBlock(materialBlock)
                            for (slot in storageSize until storageSize + 36) {
                                val stack = handler.slots[slot].item
                                if (!stack.isEmpty && stack.item is BlockItem) {
                                    val block = (stack.item as BlockItem).block
                                    val canonical = module.getCanonicalBlock(block)
                                    if (canonical != targetCanonical && !module.isBlockNeededOnCurrentLayer(canonical)) {
                                        slotToFree = slot
                                        break
                                    }
                                }
                            }
                        }
                        if (slotToFree == -1) {
                            val targetCanonical = module.getCanonicalBlock(materialBlock)
                            for (slot in storageSize until storageSize + 36) {
                                val stack = handler.slots[slot].item
                                if (!stack.isEmpty && stack.item is BlockItem) {
                                    val block = (stack.item as BlockItem).block
                                    val canonical = module.getCanonicalBlock(block)
                                    if (canonical != targetCanonical) {
                                        slotToFree = slot
                                        break
                                    }
                                }
                            }
                        }
                        
                        if (slotToFree == -1) {
                            val targetCanonical = module.getCanonicalBlock(materialBlock)
                            for (slot in storageSize until storageSize + 36) {
                                val stack = handler.slots[slot].item
                                if (!stack.isEmpty) {
                                    val isTarget = stack.item is BlockItem && module.getCanonicalBlock((stack.item as BlockItem).block) == targetCanonical
                                    if (!isTarget) {
                                        slotToFree = slot
                                        break
                                    }
                                }
                            }
                        }

                        if (slotToFree != -1) {
                            if (slotToFree == lastDiscardSlot) {
                                discardAttempts++
                            } else {
                                lastDiscardSlot = slotToFree
                                discardAttempts = 0
                            }

                            if (discardAttempts >= 2) {
                                mc.gameMode?.handleInventoryMouseClick(
                                    handler.containerId,
                                    slotToFree,
                                    1, 
                                    ClickType.THROW,
                                    p
                                )
                                discardAttempts = 0
                            } else {
                                mc.gameMode?.handleInventoryMouseClick(
                                    handler.containerId,
                                    slotToFree,
                                    0,
                                    ClickType.QUICK_MOVE,
                                    p
                                )
                            }
                            setCooldown(150)
                            return
                        }
                    }
                }

                beforeTransferCount = getMenuPlayerItemCount(handler, storageSize, materialBlock)
                if (beforeTransferCount >= targetCount()) {
                    moveTo(Phase.CLOSE_CHEST)
                    return
                }

                val materialSlot = findMaterialSlot(handler, storageSize)
                if (materialSlot == -1) {
                    if (!moveToNextChest(p)) {
                        val currentCount = getMenuPlayerItemCount(handler, storageSize, materialBlock)
                        if (currentCount == 0) {
                            outOfMaterial = true
                        }
                        moveTo(Phase.CLOSE_CHEST)
                    }
                    return
                }

                mc.gameMode?.handleInventoryMouseClick(
                    handler.containerId,
                    materialSlot,
                    0,
                    ClickType.QUICK_MOVE,
                    p
                )
                lastObservedCount = -1
                stableCountChecks = 0
                moveTo(Phase.VERIFY_TRANSFER, 200)
            }
            Phase.VERIFY_TRANSFER -> {
                val handler = currentStorageMenu()
                if (handler == null) {
                    interacted = false
                    moveTo(Phase.OPEN_CHEST)
                    return
                }

                val storageSize = storageSlotCount(handler)
                val currentCount = getMenuPlayerItemCount(handler, storageSize, materialBlock)
                if (currentCount == lastObservedCount) {
                    stableCountChecks++
                } else {
                    stableCountChecks = 0
                    lastObservedCount = currentCount
                }

                if (stableCountChecks < 2 && !phaseTimer.passedMs(500L)) {
                    setCooldown(150)
                    return
                }

                if (currentCount <= beforeTransferCount) {
                    failedTransferAttempts++
                } else {
                    failedTransferAttempts = 0
                }

                val noMaterialLeft = findMaterialSlot(handler, storageSize) == -1
                if (currentCount >= targetCount()) {
                    moveTo(Phase.CLOSE_CHEST)
                } else if ((noMaterialLeft || failedTransferAttempts >= 3) && moveToNextChest(p)) {
                    return
                } else if (noMaterialLeft || failedTransferAttempts >= 3) {
                    if (currentCount == 0) {
                        this.outOfMaterial = true
                    }
                    moveTo(Phase.CLOSE_CHEST)
                } else {
                    moveTo(Phase.TRANSFER_ITEMS)
                }
            }
            Phase.CLOSE_CHEST -> {
                p.closeContainer()
                moveTo(Phase.FINISH, 250)
            }
            Phase.FINISH -> {
                if (outOfMaterial) {
                    val module = agent.module as? ANAutoBuild
                    if (module != null) {
                        module.building = false
                        module.sendClientMessage("绑定箱子内无可用物料: ${BuiltInRegistries.BLOCK.getKey(materialBlock).path}，已暂停自动建造。")
                    }
                } else {
                    agent.scheduler.push(AutoBuildBootTask(agent))
                }
                finished = true
            }
        }
    }

    override fun stop() {
        BaritoneHelper.cancel()
        player?.closeContainer()
    }

    private fun currentStorageMenu(): AbstractContainerMenu? {
        val menu = player?.containerMenu ?: return null
        return if (menu is ChestMenu || menu is ShulkerBoxMenu) menu else null
    }

    private fun storageSlotCount(menu: AbstractContainerMenu): Int =
        (menu.slots.size - PLAYER_INVENTORY_MENU_SLOTS).coerceAtLeast(0)

    private fun findMaterialSlot(menu: AbstractContainerMenu, storageSize: Int): Int {
        val module = agent.module as? ANAutoBuild ?: return -1
        val canonical = module.getCanonicalBlock(materialBlock)
        for (slot in 0 until storageSize) {
            val stack = menu.slots[slot].item
            val item = stack.item
            if (!stack.isEmpty && item is BlockItem && module.getCanonicalBlock(item.block) == canonical) {
                return slot
            }
        }
        return -1
    }

    private fun getMenuPlayerItemCount(menu: AbstractContainerMenu, storageSize: Int, block: Block): Int {
        val module = agent.module as? ANAutoBuild ?: return 0
        val canonical = module.getCanonicalBlock(block)
        var count = 0
        for (slot in storageSize until menu.slots.size) {
            val stack = menu.slots[slot].item
            val item = stack.item
            if (!stack.isEmpty && item is BlockItem && module.getCanonicalBlock(item.block) == canonical) {
                count += stack.count
            }
        }
        return count
    }

    private fun moveToNextChest(player: LocalPlayer): Boolean {
        if (chestIndex + 1 >= refillChests.size) return false
        player.closeContainer()
        chestIndex++
        currentChestPos = refillChests[chestIndex]
        interacted = false
        beforeTransferCount = 0
        lastObservedCount = -1
        stableCountChecks = 0
        failedTransferAttempts = 0
        moveTo(Phase.WALK_TO_CHEST, 150)
        return true
    }

    private fun targetCount(): Int {
        val maxStacks = 5
        return maxStacks * 64
    }

    private companion object {
        const val PLAYER_INVENTORY_MENU_SLOTS = 36
    }
}

enum class RefillType {
    MISSING,
    LOW
}
