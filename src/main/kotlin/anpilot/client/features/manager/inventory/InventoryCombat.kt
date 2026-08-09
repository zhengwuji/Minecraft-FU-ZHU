package anpilot.client.features.manager.inventory

import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.*
import net.minecraft.world.item.enchantment.EnchantmentHelper

object InventoryCombat {

    fun swordScore(stack: ItemStack): Float {
        if (!stack.`is`(ItemTags.SWORDS)) return 0f

        val baseDamage = stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
            .get(Attributes.ATTACK_DAMAGE)
            .sumOf { it.amount }
            .toFloat()

        val sharpness = InventoryCache.sharpness()
        val enchantDamage = if (sharpness != null) {
            EnchantmentHelper.getItemEnchantmentLevel(sharpness, stack).toFloat()
        } else 0f

        return baseDamage + enchantDamage
    }

    fun axeScore(stack: ItemStack): Float {
        if (stack.item !is AxeItem) return 0f

        val baseDamage = stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
            .get(Attributes.ATTACK_DAMAGE)
            .sumOf { it.amount }
            .toFloat()

        val sharpness = InventoryCache.sharpness()
        val enchantDamage = if (sharpness != null) {
            EnchantmentHelper.getItemEnchantmentLevel(sharpness, stack).toFloat()
        } else 0f

        return baseDamage + enchantDamage
    }

    fun pickaxeScore(stack: ItemStack): Float {
        val efficiency = InventoryCache.efficiency()
        return if (efficiency != null) {
            EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack).toFloat()
        } else 0f
    }
}
