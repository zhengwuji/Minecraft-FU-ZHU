package anpilot.client.features.ai.task.autoenchant

import net.minecraft.world.item.Item
import net.minecraft.world.item.enchantment.Enchantment

data class EnchantSpec(
    val enchantment: Enchantment,
    val level: Int?,
    val label: String,
    val applicableItems: List<Item>,
    val enabled: Boolean
)

data class BookRequest(
    val spec: EnchantSpec,
    val count: Int
)

data class GearRequest(
    val items: List<Item>,
    val label: String,
    val count: Int
)

data class EnchantStage(
    val name: String,
    val entries: List<StageEntry>
) {
    fun specs(selected: List<EnchantSpec>): List<EnchantSpec> =
        entries.mapNotNull { it.resolve(selected) }
}

data class StageEntry(
    val labels: List<String>
) {
    fun resolve(selected: List<EnchantSpec>): EnchantSpec? =
        labels.firstNotNullOfOrNull { label -> selected.firstOrNull { it.label == label } }

    companion object {
        fun one(label: String): StageEntry = StageEntry(listOf(label))
        fun choice(vararg labels: String): StageEntry = StageEntry(labels.toList())
    }
}
