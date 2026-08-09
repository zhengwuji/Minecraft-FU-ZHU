package anpilot.client.features.manager.inventory

import net.minecraft.world.item.Item
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.Enchantments

object InventoryCache {
    private var sharpness: Enchantment? = null
    private var efficiency: Enchantment? = null

    val itemCache = HashMap<String, Item>()

    fun init() {
        sharpness = Enchantments.SHARPNESS
        efficiency = Enchantments.BLOCK_EFFICIENCY
    }

    fun sharpness() = sharpness
    fun efficiency() = efficiency
}
