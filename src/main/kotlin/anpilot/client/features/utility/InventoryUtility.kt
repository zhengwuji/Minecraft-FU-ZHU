package anpilot.client.features.utility

import anpilot.client.features.manager.inventory.Inventory
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BedItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import anpilot.client.features.manager.inventory.SearchInvResult
import net.minecraft.world.item.enchantment.Enchantments





object InventoryUtility {
    private val mc = Minecraft.getInstance()

    fun getItemCount(item: Item): Int = Inventory.getItemCount(item)

    fun getAxe(): SearchInvResult = convert(
        Inventory.findBest(0 until Inventory.MAIN_SIZE, ::isAxe, ::weaponScore)
    )

    fun getAxeHotBar(): SearchInvResult = convert(
        Inventory.findBest(0 until Inventory.HOTBAR_SIZE, ::isAxe, ::weaponScore)
    )

    fun getSword(): SearchInvResult = convert(
        Inventory.findBest(0 until Inventory.MAIN_SIZE, { it.`is`(ItemTags.SWORDS) }, ::weaponScore)
    )

    fun getSwordHotBar(): SearchInvResult = convert(
        Inventory.findBest(0 until Inventory.HOTBAR_SIZE, { it.`is`(ItemTags.SWORDS) }, ::weaponScore)
    )

    fun getPickAxe(): SearchInvResult = convert(
        Inventory.findBest(0 until Inventory.MAIN_SIZE, { it.`is`(ItemTags.PICKAXES) }, ::toolScore)
    )

    fun getPickAxeHotbar(): SearchInvResult = convert(
        Inventory.findBest(0 until Inventory.HOTBAR_SIZE, { it.`is`(ItemTags.PICKAXES) }, ::toolScore)
    )

    fun getPickAxeHotBar(): SearchInvResult = getPickAxeHotbar()

    fun getSkull(): SearchInvResult = findInHotBar { stack ->
        stack.`is`(Items.SKELETON_SKULL) ||
            stack.`is`(Items.WITHER_SKELETON_SKULL) ||
            stack.`is`(Items.CREEPER_HEAD) ||
            stack.`is`(Items.PLAYER_HEAD) ||
            stack.`is`(Items.ZOMBIE_HEAD)
    }

    fun getElytra(): Int {
        val player = mc.player ?: return -1
        val chest = player.getItemBySlot(EquipmentSlot.CHEST)
        if (chest.`is`(Items.ELYTRA) && chest.damageValue < chest.maxDamage - 1) return -2

        for (slot in 0 until Inventory.MAIN_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (stack.`is`(Items.ELYTRA) && stack.damageValue < stack.maxDamage - 1) {
                return if (slot < Inventory.HOTBAR_SIZE) Inventory.toMenuSlot(slot) else slot
            }
        }

        return -1
    }

    fun findInHotBar(searcher: Searcher): SearchInvResult =
        convert(Inventory.findInHotbar { searcher.isValid(it) })

    fun findItemInHotBar(items: List<Item>): SearchInvResult =
        convert(Inventory.findItemInHotbar(items))

    fun findItemInHotBar(vararg items: Item): SearchInvResult =
        findItemInHotBar(items.toList())

    fun findInInventory(searcher: Searcher): SearchInvResult =
        convert(Inventory.findInInventory { searcher.isValid(it) })

    fun findItemInInventory(items: List<Item>): SearchInvResult =
        convert(Inventory.findItemInInventory(items))

    fun findItemInInventory(vararg items: Item): SearchInvResult =
        findItemInInventory(items.toList())

    fun findBlockInHotBar(blocks: List<Block>): SearchInvResult =
        findItemInHotBar(blocks.map { it.asItem() })

    fun findBlockInHotBar(vararg blocks: Block): SearchInvResult =
        findBlockInHotBar(blocks.toList())

    fun findBlockInInventory(blocks: List<Block>): SearchInvResult =
        findItemInInventory(blocks.map { it.asItem() })

    fun findBlockInInventory(vararg blocks: Block): SearchInvResult =
        findBlockInInventory(blocks.toList())

    fun saveSlot() {
        Inventory.save()
    }

    fun returnSlot() {
        Inventory.restore()
    }

    fun switchTo(slot: Int) {
        Inventory.switchTo(slot)
    }

    fun switchToSilent(slot: Int) {
        Inventory.switchSilent(slot)
    }

    fun getAntiWeaknessItem(): SearchInvResult =
        convert(Inventory.getAntiWeaknessItem())

    fun getHitDamage(weapon: ItemStack, ent: Player): Float =
        Inventory.getHitDamage(weapon, ent)

    fun findBedInHotBar(): SearchInvResult =
        findInHotBar { stack -> stack.item is BedItem }

    fun findBed(): SearchInvResult =
        findInInventory { stack -> stack.item is BedItem }

    fun getItem(name: String?): Item {
        if (name == null) return Items.AIR
        val lowercaseName = name.lowercase()

        for (block in BuiltInRegistries.BLOCK) {
            val id = block.descriptionId.removePrefix("block.minecraft.")
            if (id.equals(lowercaseName, ignoreCase = true)) return block.asItem()
        }

        for (item in BuiltInRegistries.ITEM) {
            val id = item.descriptionId.removePrefix("item.minecraft.")
            if (id.equals(lowercaseName, ignoreCase = true)) return item
        }

        return Items.DIRT
    }

    fun getBedsCount(): Int {
        val player = mc.player ?: return 0
        var count = 0
        for (slot in 0 until Inventory.MAIN_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (stack.item is BedItem) count += stack.count
        }
        return count
    }

    private fun isAxe(stack: ItemStack): Boolean =
        stack.`is`(ItemTags.AXES) || stack.item is AxeItem

    private fun weaponScore(stack: ItemStack): Float =
        Inventory.materialRank(stack).toFloat() +
            Inventory.getEnchantmentLevel(stack, Enchantments.SHARPNESS)

    private fun toolScore(stack: ItemStack): Float =
        Inventory.materialRank(stack).toFloat() +
            Inventory.getEnchantmentLevel(stack, Enchantments.BLOCK_EFFICIENCY)

    private fun convert(result: SearchInvResult): SearchInvResult =
        SearchInvResult(result.slot, result.found, result.stack)

    fun interface Searcher {
        fun isValid(stack: ItemStack): Boolean
    }
}

data class SearchInvResult(val slot: Int, val found: Boolean, val stack: ItemStack = ItemStack.EMPTY) {
    companion object {
        fun notFound(): SearchInvResult = SearchInvResult(-1, false)
    }
}
