package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ElytraItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import java.util.Arrays
import java.util.Comparator

class ANAutoArmour : ANBaseModule(
    name = "AutoArmour",
    description = "自动评估背包中装备属性并替换穿上综合防御值最高的护甲",
    category = ANModuleCategory.PLAYER,
    chineseName = "自动装备"
) {
    val delay = addSetting(ANSetting("Delay", 1f, 0f, 5f))
    val antiBreak = addSetting(ANSetting("AntiBreak", false))

    private val armorPieces = arrayOf(
        ArmorPiece(EquipmentSlot.HEAD),
        ArmorPiece(EquipmentSlot.CHEST),
        ArmorPiece(EquipmentSlot.LEGS),
        ArmorPiece(EquipmentSlot.FEET)
    )
    private var timer = 0

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (timer > 0) {
            timer--
            return
        }

        for (armorPiece in armorPieces) armorPiece.reset()
        var emptySlot = -1
        for (i in 0 until 36) {
            val itemStack = player.inventory.getItem(i)
            if (!player.containerMenu.carried.isEmpty) {
                if (itemStack.isEmpty || !isArmor(itemStack)) {
                    emptySlot = i
                    break
                }
                if (emptySlot != -1) {
                    click(toMenuSlot(emptySlot))
                } else {
                    dropHand()
                }
            } else {
                if (itemStack.isEmpty || !isArmor(itemStack)) continue

                if (antiBreak.value && itemStack.isDamageableItem && itemStack.maxDamage - itemStack.damageValue <= 10) {
                    continue
                }

                if (hasAvoidedEnchantment(itemStack)) continue

                when (getItemSlotId(itemStack)) {
                    0 -> armorPieces[3].add(itemStack, i)
                    1 -> armorPieces[2].add(itemStack, i)
                    2 -> armorPieces[1].add(itemStack, i)
                    3 -> armorPieces[0].add(itemStack, i)
                }
            }
        }

        for (armorPiece in armorPieces) armorPiece.calculate()
        Arrays.sort(armorPieces, Comparator.comparingInt(ArmorPiece::getSortScore))
        for (armorPiece in armorPieces) armorPiece.apply()
    }

    private fun hasAvoidedEnchantment(itemStack: ItemStack): Boolean {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BINDING_CURSE, itemStack) > 0
    }

    private fun getItemSlotId(itemStack: ItemStack): Int {
        if (itemStack.item is ElytraItem) return 2
        val slot = (itemStack.item as? ArmorItem)?.equipmentSlot ?: Mob.getEquipmentSlotForItem(itemStack)
        return armorSlotId(slot)
    }

    private fun getScore(itemStack: ItemStack): Int {
        if (itemStack.isEmpty) return 0

        var score = 0

        score += 3 * EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLAST_PROTECTION, itemStack)
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, itemStack)
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FIRE_PROTECTION, itemStack)
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PROJECTILE_PROTECTION, itemStack)
        score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.UNBREAKING, itemStack)
        score += 2 * EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, itemStack)

        val slot = (itemStack.item as? ArmorItem)?.equipmentSlot ?: EquipmentSlot.CHEST
        val modifiers = itemStack.getAttributeModifiers(slot)
        for (entry in modifiers.entries()) {
            if (entry.key == Attributes.ARMOR || entry.key == Attributes.ARMOR_TOUGHNESS) {
                val mod = entry.value
                score += mod.amount.toInt()
            }
        }

        return score
    }

    private fun cannotSwap(): Boolean = timer > 0

    private fun swap(from: Int, armorSlotId: Int) {
        move(from, armorSlotId)
        timer = delay.value.toInt()
    }

    private fun moveToEmpty(armorSlotId: Int) {
        val player = Minecraft.getInstance().player ?: return
        if (!player.getItemBySlot(equipmentSlotFromArmorId(armorSlotId)).isEmpty) {
            shiftClickArmor(armorSlotId)
            timer = delay.value.toInt()
        }
    }

    private fun isArmor(itemStack: ItemStack): Boolean {
        return itemStack.item is ArmorItem || itemStack.item is ElytraItem
    }

    private inner class ArmorPiece(private val slot: EquipmentSlot) {
        private var bestSlot = 0
        private var bestScore = 0

        private var score = 0
        private var durability = 0

        fun reset() {
            bestSlot = -1
            bestScore = -1
            score = -1
            durability = Int.MAX_VALUE
        }

        fun add(itemStack: ItemStack, slot: Int) {
            val score = getScore(itemStack)

            if (score > bestScore) {
                bestScore = score
                bestSlot = slot
            }
        }

        fun calculate() {
            val player = Minecraft.getInstance().player ?: return
            if (cannotSwap()) return

            val itemStack = player.getItemBySlot(slot)

            if (itemStack.`is`(Items.ELYTRA) && !player.onGround()) {
                score = Int.MAX_VALUE
                return
            }

            if (hasAvoidedEnchantment(itemStack)) {
                score = Int.MAX_VALUE
                return
            }

            score = getScore(itemStack)
            score = decreaseScoreByAvoidedEnchantments(score, itemStack)
            score = applyAntiBreakScore(score, itemStack)

            if (!itemStack.isEmpty) {
                durability = itemStack.maxDamage - itemStack.damageValue
            }
        }

        fun getSortScore(): Int {
            if (antiBreak.value && durability <= 10) return -1
            return bestScore
        }

        fun apply() {
            if (cannotSwap() || score == Int.MAX_VALUE) return

            if (bestScore > score) swap(bestSlot, armorSlotId(slot))
            else if (antiBreak.value && durability <= 10) {
                moveToEmpty(armorSlotId(slot))
            }
        }

        private fun decreaseScoreByAvoidedEnchantments(score: Int, itemStack: ItemStack): Int {
            return score - 2 * EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BINDING_CURSE, itemStack)
        }

        private fun applyAntiBreakScore(score: Int, itemStack: ItemStack): Int {
            if (antiBreak.value && itemStack.isDamageableItem && itemStack.maxDamage - itemStack.damageValue <= 10) {
                return -1
            }

            return score
        }
    }

    private fun move(from: Int, armorSlotId: Int) {
        val fromId = toMenuSlot(from)
        val toId = armorMenuSlot(armorSlotId)
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val gameMode = minecraft.gameMode ?: return
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, fromId, 0, ClickType.PICKUP, player)
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, toId, 0, ClickType.PICKUP, player)
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, fromId, 0, ClickType.PICKUP, player)
    }

    private fun click(slot: Int) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        minecraft.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, slot, 0, ClickType.PICKUP, player)
    }

    private fun shiftClickArmor(armorSlotId: Int) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        minecraft.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, armorMenuSlot(armorSlotId), 0, ClickType.QUICK_MOVE, player)
    }

    private fun dropHand() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (!player.containerMenu.carried.isEmpty) {
            minecraft.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, -999, 0, ClickType.PICKUP, player)
        }
    }

    private fun armorMenuSlot(armorSlotId: Int): Int = InventoryMenu.ARMOR_SLOT_START + (3 - armorSlotId)

    private fun armorSlotId(slot: EquipmentSlot): Int = when (slot) {
        EquipmentSlot.FEET -> 0
        EquipmentSlot.LEGS -> 1
        EquipmentSlot.CHEST -> 2
        EquipmentSlot.HEAD -> 3
        else -> -1
    }

    private fun equipmentSlotFromArmorId(armorSlotId: Int): EquipmentSlot = when (armorSlotId) {
        0 -> EquipmentSlot.FEET
        1 -> EquipmentSlot.LEGS
        2 -> EquipmentSlot.CHEST
        3 -> EquipmentSlot.HEAD
        else -> EquipmentSlot.CHEST
    }

    private fun toMenuSlot(slot: Int): Int = if (slot < 9) InventoryMenu.USE_ROW_SLOT_START + slot else slot
}
