package anpilot.client.features.manager.inventory

import anpilot.client.features.event.impl.PacketEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.Block

enum class SilentSwapType {
    HOTBAR,
    INVENTORY
}

data class InventoryItemSlot(
    val slot: Int,
    val stack: ItemStack = ItemStack.EMPTY
) {
    val found: Boolean
        get() = slot != Inventory.INVALID_SLOT && !stack.isEmpty
}

data class LowItemEntry(
    val slot: Int,
    val stack: ItemStack
)

object Inventory {
    const val INVALID_SLOT = -1
    const val OFFHAND_INVENTORY_SLOT = 40
    const val OFFHAND_MENU_SLOT = InventoryMenu.SHIELD_SLOT
    const val HOTBAR_SIZE = 9
    const val MAIN_SIZE = 36

    private const val STALE_SWAP_MS = 500L

    private val mc = Minecraft.getInstance()

    private var cachedSlot = INVALID_SLOT
    private var previousSlot = INVALID_SLOT
    private var serverSlot = INVALID_SLOT

    private val currentSwap = MutableSwap()
    private val multitickSwap = MutableSwap()

    private val trackedSwaps = ArrayDeque<SwapSnapshot>()

    val selectedSlot: Int
        get() = mc.player?.inventory?.selected ?: INVALID_SLOT

    val isSwapped: Boolean
        get() = currentSwap.active

    val isMultitickSwapped: Boolean
        get() = multitickSwap.active

    val isSilentSwapping: Boolean
        get() {
            val player = mc.player ?: return false
            return currentSwap.active || multitickSwap.active || normalizedServerSlot(player) != player.inventory.selected
        }

    fun reset() {
        cachedSlot = INVALID_SLOT
        previousSlot = INVALID_SLOT
        serverSlot = mc.player?.inventory?.selected ?: INVALID_SLOT
        currentSwap.reset()
        multitickSwap.reset()
        trackedSwaps.clear()
    }

    fun save() {
        cachedSlot = selectedSlot
    }

    fun restore() {
        val slot = cachedSlot
        if (slot != INVALID_SLOT) {
            switchTo(slot)
        }
        cachedSlot = INVALID_SLOT
    }

    fun switchTo(slot: Int): Boolean {
        val player = mc.player ?: return false
        if (!isHotbarSlot(slot)) return false

        if (!isHotbarSlot(serverSlot)) {
            serverSlot = player.inventory.selected
        }

        if (player.inventory.selected != slot) {
            player.inventory.selected = slot
        }

        return sendSelectedSlot(slot)
    }

    fun switchSilent(slot: Int): Boolean {
        if (!isHotbarSlot(slot) || mc.player == null) return false
        return sendSelectedSlot(slot)
    }

    fun swap(slot: Int, swapBack: Boolean = true): Boolean {
        if (!isHotbarSlot(slot)) return false
        if (swapBack && previousSlot == INVALID_SLOT) {
            previousSlot = selectedSlot
        } else if (!swapBack) {
            previousSlot = INVALID_SLOT
        }

        return switchTo(slot)
    }

    fun swapBack(): Boolean {
        val slot = previousSlot
        if (slot == INVALID_SLOT) return false

        previousSlot = INVALID_SLOT
        return switchTo(slot)
    }

    fun startSwap(itemSlot: Int, type: SilentSwapType = SilentSwapType.HOTBAR): Boolean {
        val player = mc.player ?: return false
        if (currentSwap.active) return currentSwap.slotTo == itemSlot

        val selected = player.inventory.selected
        if (itemSlot == selected) {
            currentSwap.set(type, selected, itemSlot)
            return true
        }

        return when {
            isHotbarSlot(itemSlot) -> {
                currentSwap.set(SilentSwapType.HOTBAR, selected, itemSlot)
                trackedSwaps.add(SwapSnapshot(selected, itemSlot, copyHotbar()))
                sendSelectedSlot(itemSlot)
            }

            type == SilentSwapType.INVENTORY && isMainInventorySlot(itemSlot) -> {
                currentSwap.set(SilentSwapType.INVENTORY, selected, itemSlot)
                trackedSwaps.add(SwapSnapshot(selected, itemSlot, copyHotbar()))
                swapInventorySlot(itemSlot, selected)
            }

            else -> false
        }
    }

    fun endSwap(type: SilentSwapType? = null): Boolean {
        val player = mc.player ?: return false
        if (!currentSwap.active) return false

        val swapType = type ?: currentSwap.type
        val restored = when (swapType) {
            SilentSwapType.HOTBAR -> sendSelectedSlot(player.inventory.selected)
            SilentSwapType.INVENTORY -> swapInventorySlot(currentSwap.slotTo, currentSwap.slotFrom)
        }

        currentSwap.reset()
        pruneTrackedSwaps()
        return restored
    }

    fun startMultitickSwap(hotbarSlot: Int): Boolean {
        if (!isHotbarSlot(hotbarSlot) || currentSwap.active || multitickSwap.active) return false

        val selected = selectedSlot
        multitickSwap.set(SilentSwapType.HOTBAR, selected, hotbarSlot)
        return sendSelectedSlot(hotbarSlot)
    }

    fun endMultitickSwap(): Boolean {
        val player = mc.player ?: return false
        if (!multitickSwap.active) return false

        val restored = sendSelectedSlot(player.inventory.selected)
        multitickSwap.reset()
        return restored
    }

    fun serverStack(): ItemStack {
        val player = mc.player ?: return ItemStack.EMPTY
        val slot = normalizedServerSlot(player)
        return if (isHotbarSlot(slot)) player.inventory.getItem(slot) else player.mainHandItem
    }

    fun isHolding(item: Item, hand: InteractionHand = InteractionHand.MAIN_HAND): Boolean {
        val player = mc.player ?: return false
        val stack =
            if (hand == InteractionHand.MAIN_HAND && isSilentSwapping) serverStack() else player.getItemInHand(hand)
        return stack.`is`(item)
    }

    fun find(range: IntRange, predicate: (ItemStack) -> Boolean): SearchInvResult {
        val player = mc.player ?: return SearchInvResult.notFound()
        for (slot in range) {
            if (!isReadableInventorySlot(slot)) continue
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && predicate(stack)) {
                return SearchInvResult(slot, true, stack)
            }
        }

        return SearchInvResult.notFound()
    }

    fun findBest(
        range: IntRange,
        predicate: (ItemStack) -> Boolean,
        score: (ItemStack) -> Float
    ): SearchInvResult {
        val player = mc.player ?: return SearchInvResult.notFound()
        var bestSlot = INVALID_SLOT
        var bestScore = Float.NEGATIVE_INFINITY

        for (slot in range) {
            if (!isReadableInventorySlot(slot)) continue
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !predicate(stack)) continue

            val value = score(stack)
            if (value > bestScore) {
                bestScore = value
                bestSlot = slot
            }
        }

        return if (bestSlot == INVALID_SLOT) {
            SearchInvResult.notFound()
        } else {
            SearchInvResult(bestSlot, true, player.inventory.getItem(bestSlot))
        }
    }

    fun findInHotbar(predicate: (ItemStack) -> Boolean): SearchInvResult =
        find(0 until HOTBAR_SIZE, predicate)

    fun findInInventory(predicate: (ItemStack) -> Boolean): SearchInvResult {
        val player = mc.player ?: return SearchInvResult.notFound()

        for (slot in MAIN_SIZE - 1 downTo 0) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !predicate(stack)) continue
            return SearchInvResult(toMenuSlot(slot), true, stack)
        }

        return SearchInvResult.notFound()
    }

    fun findItemInHotbar(items: Collection<Item>): SearchInvResult =
        findInHotbar { stack -> stack.item in items }

    fun findItemInHotbar(vararg items: Item): SearchInvResult =
        findItemInHotbar(items.asList())

    fun findItemInInventory(items: Collection<Item>): SearchInvResult =
        findInInventory { stack -> stack.item in items }

    fun findItemInInventory(vararg items: Item): SearchInvResult =
        findItemInInventory(items.asList())

    fun findBlockInHotbar(blocks: Collection<Block>): SearchInvResult =
        findItemInHotbar(blocks.map(Block::asItem))

    fun findBlockInHotbar(vararg blocks: Block): SearchInvResult =
        findBlockInHotbar(blocks.asList())

    fun findBlockInInventory(blocks: Collection<Block>): SearchInvResult =
        findItemInInventory(blocks.map(Block::asItem))

    fun findBlockInInventory(vararg blocks: Block): SearchInvResult =
        findBlockInInventory(blocks.asList())

    fun getItemCount(item: Item): Int {
        val player = mc.player ?: return 0
        var count = 0

        for (slot in 0 until MAIN_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (stack.`is`(item)) count += stack.count
        }

        val offhand = player.inventory.getItem(OFFHAND_INVENTORY_SLOT)
        if (offhand.`is`(item)) count += offhand.count
        return count
    }

    fun hotbarLowCountItems(threshold: Int): List<LowItemEntry> {
        val player = mc.player ?: return emptyList()
        val result = ArrayList<LowItemEntry>()

        for (slot in 0 until HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !stack.isStackable || stack.count >= threshold) continue
            result.add(LowItemEntry(slot, stack))
        }

        return result
    }

    fun findMatchingInventorySlot(target: ItemStack): Int {
        val player = mc.player ?: return INVALID_SLOT
        if (target.isEmpty) return INVALID_SLOT

        for (slot in HOTBAR_SIZE until MAIN_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty && ItemStack.isSameItemSameTags(stack, target)) {
                return slot
            }
        }

        return INVALID_SLOT
    }

    fun getInventoryAndHotbarSlots(copy: Boolean = false): Map<Int, ItemStack> {
        val player = mc.player ?: return emptyMap()
        val map = LinkedHashMap<Int, ItemStack>(MAIN_SIZE)

        for (slot in 0 until MAIN_SIZE) {
            val stack = player.inventory.getItem(slot)
            map[slot] = if (copy) stack.copy() else stack
        }

        return map
    }

    fun getEquipmentStack(slot: EquipmentSlot): ItemStack {
        val player = mc.player ?: return ItemStack.EMPTY
        return getEquipmentStack(slot, player)
    }

    fun getEquipmentStack(slot: EquipmentSlot, player: Player): ItemStack =
        player.getItemBySlot(slot)

    fun getAllEquipmentStacks(): Array<ItemStack> {
        val player = mc.player ?: return emptyArray()
        return getAllEquipmentStacks(player)
    }

    fun getAllEquipmentStacks(player: Player): Array<ItemStack> =
        arrayOf(
            getEquipmentStack(EquipmentSlot.HEAD, player),
            getEquipmentStack(EquipmentSlot.CHEST, player),
            getEquipmentStack(EquipmentSlot.LEGS, player),
            getEquipmentStack(EquipmentSlot.FEET, player)
        )

    fun getAntiWeaknessItem(): SearchInvResult {
        val player = mc.player ?: return SearchInvResult.notFound()

        if (isWeaknessBypass(player.mainHandItem)) {
            return SearchInvResult(player.inventory.selected, true, player.mainHandItem)
        }

        return findInHotbar(::isWeaknessBypass)
    }

    fun getEnchantmentLevel(stack: ItemStack, enchantment: Enchantment): Int {
        if (stack.isEmpty) return 0
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack)
    }

    fun hasEnchantment(stack: ItemStack, enchantment: Enchantment): Boolean =
        getEnchantmentLevel(stack, enchantment) > 0

    fun hasEnchantments(stack: ItemStack, vararg enchantments: Enchantment): Boolean =
        enchantments.all { hasEnchantment(stack, it) }

    fun getHitDamage(weapon: ItemStack, target: Player): Float {
        val player = mc.player ?: return 0f
        var baseDamage = when {
            weapon.`is`(ItemTags.SWORDS) -> 7f
            weapon.`is`(ItemTags.AXES) || weapon.item is AxeItem -> 9f
            else -> 1f
        }

        if (player.fallDistance > 0f) {
            baseDamage += baseDamage / 2f
        }

        val strength = player.getEffect(MobEffects.DAMAGE_BOOST)
        if (strength != null) {
            baseDamage += 3f * (strength.amplifier + 1)
        }

        val totalArmor = target.armorValue.toFloat()
        val toughness = target.getAttributeValue(Attributes.ARMOR_TOUGHNESS).toFloat()
        val toughnessFactor = 2f + toughness / 4f
        val realArmor = Mth.clamp(totalArmor - baseDamage / toughnessFactor, totalArmor * 0.2f, 20f)
        return baseDamage * (1f - realArmor / 25f)
    }

    fun materialRank(stack: ItemStack): Int {
        val key = stack.item.descriptionId
        return when {
            key.contains("netherite") -> 600
            key.contains("diamond") -> 500
            key.contains("iron") -> 400
            key.contains("gold") -> 300
            key.contains("stone") -> 200
            key.contains("wood") -> 100
            else -> 0
        }
    }

    fun isInInventoryScreen(): Boolean =
        mc.screen is AbstractContainerScreen<*>

    fun toMenuSlot(slot: Int): Int = when {
        slot == OFFHAND_INVENTORY_SLOT -> OFFHAND_MENU_SLOT
        slot in 0 until HOTBAR_SIZE -> InventoryMenu.USE_ROW_SLOT_START + slot
        else -> slot
    }

    fun armorMenuSlot(armorSlotId: Int): Int =
        InventoryMenu.ARMOR_SLOT_START + (3 - armorSlotId)

    fun armorSlotId(slot: EquipmentSlot): Int = when (slot) {
        EquipmentSlot.FEET -> 0
        EquipmentSlot.LEGS -> 1
        EquipmentSlot.CHEST -> 2
        EquipmentSlot.HEAD -> 3
        else -> INVALID_SLOT
    }

    fun pickup(slot: Int): Boolean =
        click(toMenuSlot(slot), 0, ClickType.PICKUP)

    fun quickMove(slot: Int): Boolean =
        click(toMenuSlot(slot), 0, ClickType.QUICK_MOVE)

    fun drop(slot: Int, all: Boolean = true): Boolean =
        click(toMenuSlot(slot), if (all) 1 else 0, ClickType.THROW)

    fun dropHand(): Boolean {
        val player = mc.player ?: return false
        if (player.containerMenu.carried.isEmpty) return true
        return click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0, ClickType.PICKUP)
    }

    fun swapInventorySlot(slot: Int, hotbarSlot: Int): Boolean {
        if (!isHotbarSlot(hotbarSlot)) return false
        return click(toMenuSlot(slot), hotbarSlot, ClickType.SWAP)
    }

    fun move(from: Int, to: Int): Boolean {
        if (!pickup(from)) return false
        if (!pickup(to)) return false
        return pickup(from)
    }

    fun refillSlot(hotbarSlot: Int, inventorySlot: Int): Boolean {
        if (!isHotbarSlot(hotbarSlot) || !isMainInventorySlot(inventorySlot)) return false
        return move(inventorySlot, hotbarSlot)
    }

    fun ensureCursorEmpty(): Boolean {
        val player = mc.player ?: return false
        if (player.containerMenu.carried.isEmpty) return true

        for (slot in 0 until MAIN_SIZE) {
            if (player.inventory.getItem(slot).isEmpty) {
                return pickup(slot)
            }
        }

        return dropHand()
    }

    fun click(slot: Int, button: Int, input: ClickType): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot, button, input, player)
        return true
    }

    fun useBestSlot(
        slot: Int,
        type: SilentSwapType = if (isHotbarSlot(slot)) SilentSwapType.HOTBAR else SilentSwapType.INVENTORY,
        action: () -> Unit
    ): Boolean {
        if (!startSwap(slot, type)) return false
        return try {
            action()
            true
        } finally {
            endSwap(type)
        }
    }

    fun onPacketOutbound(packet: Packet<*>) {
        if (packet is ServerboundSetCarriedItemPacket) {
            serverSlot = packet.slot
        }
    }

    fun onPacketOutbound(event: PacketEvent.Outbound) {
        val packet = event.packet
        if (packet is ServerboundSetCarriedItemPacket) {
            if (normalizedServerSlot(mc.player) == packet.slot) {
                event.setCancelled(true)
                return
            }
            serverSlot = packet.slot
        }
    }

    private fun sendSelectedSlot(slot: Int): Boolean {
        val player = mc.player ?: return false
        if (!isHotbarSlot(slot)) return false

        if (serverSlot == INVALID_SLOT) {
            serverSlot = player.inventory.selected
        }

        if (serverSlot == slot) return true

        mc.connection?.send(ServerboundSetCarriedItemPacket(slot)) ?: return false
        serverSlot = slot
        return true
    }

    private fun normalizedServerSlot(player: Player?): Int {
        if (player == null) return INVALID_SLOT
        if (!isHotbarSlot(serverSlot)) {
            serverSlot = player.inventory.selected
        }
        return serverSlot
    }

    private fun copyHotbar(): Array<ItemStack> {
        val player = mc.player ?: return emptyArray()
        return Array(HOTBAR_SIZE) { slot -> player.inventory.getItem(slot).copy() }
    }

    private fun pruneTrackedSwaps() {
        val now = System.currentTimeMillis()
        while (trackedSwaps.isNotEmpty() && now - trackedSwaps.first().time > STALE_SWAP_MS) {
            trackedSwaps.removeFirst()
        }
    }

    private fun isHotbarSlot(slot: Int): Boolean =
        slot in 0 until HOTBAR_SIZE

    private fun isMainInventorySlot(slot: Int): Boolean =
        slot in HOTBAR_SIZE until MAIN_SIZE

    private fun isReadableInventorySlot(slot: Int): Boolean =
        slot in 0 until MAIN_SIZE || slot == OFFHAND_INVENTORY_SLOT

    private fun isWeaknessBypass(stack: ItemStack): Boolean =
        stack.`is`(ItemTags.SWORDS) ||
                stack.`is`(ItemTags.PICKAXES) ||
                stack.`is`(ItemTags.AXES) ||
                stack.`is`(ItemTags.SHOVELS) ||
                stack.item is AxeItem ||
                stack.item is ShovelItem

    private class MutableSwap {
        var active = false
            private set
        var type = SilentSwapType.HOTBAR
            private set
        var slotFrom = INVALID_SLOT
            private set
        var slotTo = INVALID_SLOT
            private set

        fun set(type: SilentSwapType, slotFrom: Int, slotTo: Int) {
            this.active = true
            this.type = type
            this.slotFrom = slotFrom
            this.slotTo = slotTo
        }

        fun reset() {
            active = false
            type = SilentSwapType.HOTBAR
            slotFrom = INVALID_SLOT
            slotTo = INVALID_SLOT
        }
    }

    private data class SwapSnapshot(
        val from: Int,
        val to: Int,
        val hotbar: Array<ItemStack>,
        val time: Long = System.currentTimeMillis()
    )
}
