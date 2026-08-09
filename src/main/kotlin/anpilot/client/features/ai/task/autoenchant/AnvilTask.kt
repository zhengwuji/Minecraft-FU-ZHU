package anpilot.client.features.ai.task.autoenchant

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.module.player.ANAutoEnchant
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.core.Direction

class AnvilTask(
    agent: ANAgent,
    private val entries: List<StageEntry>,
    private val stageName: String
) : AITask(agent) {

    private val module: ANAutoEnchant
        get() = agent.module as ANAutoEnchant

    private var targetAnvil: BlockPos? = null
    private var state = State.APPROACH
    private var delayTimer = 0

    private enum class State {
        APPROACH,
        OPEN,
        WAIT_OPEN,
        FUSE,
        TAKE_RESULT,
        CLEANUP,
        WAIT_CLEANUP
    }

    override fun tick() {
        if (delayTimer > 0) {
            delayTimer--
            return
        }

        if (targetAnvil == null) {
            val anvil = module.availableAnvils().firstOrNull()
            if (anvil == null) {
                AgentUtils.sendMessage("无可用铁砧，中途停止")
                agent.scheduler.stop()
                finished = true
                return
            }
            targetAnvil = anvil
            state = State.APPROACH
            delayTimer = 0
        }

        val anvil = targetAnvil ?: run {
            agent.scheduler.stop()
            finished = true
            return
        }

        val mc = Minecraft.getInstance()

        if (module.isMissingAnvil(anvil)) {
            module.removeAnvil(anvil)
            val nextAnvil = module.availableAnvils().firstOrNull()
            if (nextAnvil == null) {
                AgentUtils.sendMessage("铁砧已损毁，且无替代铁砧")
                agent.scheduler.stop()
                finished = true
                return
            }
            targetAnvil = nextAnvil
            state = State.APPROACH
            return
        }

        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return

        when (state) {
            State.APPROACH -> {
                if (player.blockPosition().closerThan(anvil, 3.5)) {
                    state = State.OPEN
                } else {
                    BaritoneHelper.pathNear(anvil, 2)
                }
            }

            State.OPEN -> {
                BaritoneHelper.cancel()
                val hitResult = BlockHitResult(Vec3.atCenterOf(anvil), Direction.UP, anvil, false)
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
                state = State.WAIT_OPEN
                delayTimer = 5
            }

            State.WAIT_OPEN -> {
                val menu = player.containerMenu
                if (menu is AnvilMenu) {
                    state = State.FUSE
                } else {
                    state = State.OPEN
                }
            }

            State.FUSE -> {
                val menu = player.containerMenu as? AnvilMenu ?: run {
                    state = State.OPEN
                    return
                }

                val selected = module.selectedEnchants()
                val specs = entries.mapNotNull { it.resolve(selected) }
                val slot1 = menu.slots[0].item
                val slot2 = menu.slots[1].item
                val output = menu.slots[2].item

                if (output.isEmpty && slot1.isEmpty && slot2.isEmpty) {
                    val gearSlot = findGearToEnchant(menu, specs)
                    if (gearSlot != -1) {
                        moveItem(menu, gearSlot, 0)
                        delayTimer = 3
                        return
                    }

                    val (merge1, merge2) = findBookMerge(menu, specs) ?: ( -1 to -1 )
                    if (merge1 != -1 && merge2 != -1) {
                        moveItem(menu, merge1, 0)
                        moveItem(menu, merge2, 1)
                        delayTimer = 4
                        return
                    }

                    state = State.CLEANUP
                    return
                }

                if (!slot1.isEmpty && slot2.isEmpty) {
                    val currentSpecs = if (slot1.`is`(Items.ENCHANTED_BOOK)) {
                        bookSpecs(slot1, specs)
                    } else {
                        specs.filter { hasEnchantment(slot1, it) }.toSet()
                    }

                    val missing = specs.filter { it !in currentSpecs }
                    if (missing.isEmpty()) {
                        state = State.CLEANUP
                        return
                    }

                    val bookSlot = findMatchingBookSlot(menu, missing)
                    if (bookSlot != -1) {
                        moveItem(menu, bookSlot, 1)
                        delayTimer = 4
                        return
                    }

                    state = State.CLEANUP
                    return
                }

                if (!output.isEmpty) {
                    state = State.TAKE_RESULT
                }
            }

            State.TAKE_RESULT -> {
                val menu = player.containerMenu as? AnvilMenu ?: run {
                    state = State.OPEN
                    return
                }
                gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, player)
                delayTimer = 4
                state = State.FUSE
            }

            State.CLEANUP -> {
                val menu = player.containerMenu as? AnvilMenu ?: run {
                    finished = true
                    return
                }
                if (!menu.slots[0].item.isEmpty) {
                    gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, player)
                }
                if (!menu.slots[1].item.isEmpty) {
                    gameMode.handleInventoryMouseClick(menu.containerId, 1, 0, ClickType.QUICK_MOVE, player)
                }
                state = State.WAIT_CLEANUP
                delayTimer = 3
            }

            State.WAIT_CLEANUP -> {
                player.closeContainer()
                AgentUtils.sendMessage("阶段 [$stageName] 合成完成")
                finished = true
            }
        }
    }

    private fun findGearToEnchant(menu: AbstractContainerMenu, specs: List<EnchantSpec>): Int {
        for (slot in PLAYER_MENU_START until menu.slots.size) {
            val stack = menu.slots[slot].item
            if (module.isTargetItem(stack) && !hasAllEnchantments(stack, specs)) {
                return slot
            }
        }
        return -1
    }

    private fun findMatchingBookSlot(menu: AbstractContainerMenu, missing: List<EnchantSpec>): Int {
        for (spec in missing) {
            val slot = findBookSlotForSpec(menu, spec)
            if (slot != -1) return slot
        }
        return -1
    }

    private fun findBookSlotForSpec(menu: AbstractContainerMenu, spec: EnchantSpec): Int {
        for (slot in PLAYER_MENU_START until menu.slots.size) {
            val stack = menu.slots[slot].item
            if (module.isMatchingBook(stack, spec)) return slot
        }
        return -1
    }

    private fun hasEnchantment(stack: ItemStack, spec: EnchantSpec): Boolean {
        val level = EnchantmentHelper.getItemEnchantmentLevel(spec.enchantment, stack)
        return level > 0 && (spec.level == null || level >= spec.level)
    }

    private fun findBookWithAll(menu: AbstractContainerMenu, specs: List<EnchantSpec>): Int {
        for (slot in PLAYER_MENU_START until menu.slots.size) {
            val stack = menu.slots[slot].item
            if (bookSpecs(stack, specs).containsAll(specs)) return slot
        }
        return -1
    }

    private fun findBookMerge(menu: AbstractContainerMenu, specs: List<EnchantSpec>): Pair<Int, Int>? {
        var primarySlot = -1
        var primarySpecs = emptySet<EnchantSpec>()

        for (slot in PLAYER_MENU_START until menu.slots.size) {
            val currentSpecs = bookSpecs(menu.slots[slot].item, specs)
            if (currentSpecs.isNotEmpty() && currentSpecs.size > primarySpecs.size && currentSpecs.size < specs.size) {
                primarySlot = slot
                primarySpecs = currentSpecs
            }
        }

        if (primarySlot == -1) return null
        val missing = specs.filter { it !in primarySpecs }
        for (slot in PLAYER_MENU_START until menu.slots.size) {
            if (slot == primarySlot) continue
            val currentSpecs = bookSpecs(menu.slots[slot].item, missing)
            if (currentSpecs.isNotEmpty()) return primarySlot to slot
        }
        return null
    }

    private fun bookSpecs(stack: ItemStack, specs: List<EnchantSpec>): Set<EnchantSpec> {
        val enchantments = EnchantmentHelper.getEnchantments(stack)
        val found = LinkedHashSet<EnchantSpec>()
        for ((enchantment, level) in enchantments) {
            for (spec in specs) {
                if (enchantment == spec.enchantment && (spec.level == null || level >= spec.level)) {
                    found.add(spec)
                }
            }
        }
        return found
    }

    private fun hasAllEnchantments(stack: ItemStack, specs: List<EnchantSpec>): Boolean {
        val enchantments = EnchantmentHelper.getEnchantments(stack)
        return specs.all { spec ->
            val lvl = enchantments[spec.enchantment] ?: 0
            lvl > 0 && (spec.level == null || lvl >= spec.level)
        }
    }

    private fun moveItem(menu: AbstractContainerMenu, fromSlot: Int, toSlot: Int) {
        val player = Minecraft.getInstance().player ?: return
        val gameMode = Minecraft.getInstance().gameMode ?: return
        gameMode.handleInventoryMouseClick(menu.containerId, fromSlot, 0, ClickType.PICKUP, player)
        gameMode.handleInventoryMouseClick(menu.containerId, toSlot, 0, ClickType.PICKUP, player)
    }

    private companion object {
        const val PLAYER_MENU_START = 3
    }
}
