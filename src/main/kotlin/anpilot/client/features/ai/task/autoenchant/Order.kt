package anpilot.client.features.ai.task.autoenchant

import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

object Order {
    fun stagesFor(item: Item, specs: List<EnchantSpec>): List<EnchantStage> {
        return templateFor(item)
            .map { stage -> EnchantStage(stage.name, stage.entries) }
            .mapNotNull { stage ->
                val resolved = stage.specs(specs)
                if (resolved.isEmpty()) null else EnchantStage(stage.name, stage.entries)
            }
    }

    fun operationsFor(item: Item, specs: List<EnchantSpec>): List<EnchantSpec> =
        stagesFor(item, specs).flatMap { it.specs(specs) }.distinctBy { it.label }

    private fun templateFor(item: Item): List<EnchantStage> = when (item) {
        Items.DIAMOND_HELMET, Items.NETHERITE_HELMET -> helmet()
        Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE -> chestplate()
        Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS -> leggings()
        Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS -> boots()
        Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
        Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL -> pickaxeAndShovel()
        Items.DIAMOND_AXE, Items.NETHERITE_AXE -> axe()
        Items.DIAMOND_SWORD, Items.NETHERITE_SWORD -> sword()
        Items.ELYTRA -> elytra()
        else -> fallback()
    }

    private fun helmet() = listOf(
        stage("头盔 1", "荆棘3"),
        stage("头盔 2", choice("爆炸保护4", "保护4", "弹射物保护4", "火焰保护4"), "耐久3"),
        stage("头盔 3", "水下呼吸3", "经验修补", "水下速掘")
    )

    private fun chestplate() = listOf(
        stage("胸甲 1", "荆棘3"),
        stage("胸甲 2", "耐久3", "经验修补"),
        stage("胸甲 3", choice("爆炸保护4", "保护4", "弹射物保护4", "火焰保护4"))
    )

    private fun leggings() = listOf(
        stage("护腿 1", "迅捷潜行3"),
        stage("护腿 2", "荆棘3", "经验修补"),
        stage("护腿 3", choice("爆炸保护4", "保护4", "弹射物保护4", "火焰保护4"), "耐久3")
    )

    private fun boots() = listOf(
        stage("靴子 1", "荆棘3"),
        stage("靴子 2", "灵魂疾行3", choice("深海探索者3", "冰霜行者2")),
        stage("靴子 3", choice("爆炸保护4", "保护4", "弹射物保护4", "火焰保护4"), "摔落保护4", "耐久3", "经验修补")
    )

    private fun pickaxeAndShovel() = listOf(
        stage("镐/锹 1", choice("时运3", "精准采集"), "效率5"),
        stage("镐/锹 2", "耐久3", "经验修补")
    )

    private fun axe() = listOf(
        stage("斧 1", choice("时运3", "精准采集")),
        stage("斧 2", choice("锋利5", "节肢杀手5", "亡灵杀手5"), "耐久3"),
        stage("斧 3", "效率5", "经验修补")
    )

    private fun sword() = listOf(
        stage("剑 1", "横扫之刃3"),
        stage("剑 2", "抢夺3", choice("锋利5", "节肢杀手5", "亡灵杀手5")),
        stage("剑 3", "火焰附加2", "耐久3", "击退2", "经验修补")
    )

    private fun elytra() = listOf(
        stage("鞘翅 1", "耐久3"),
        stage("鞘翅 2", "经验修补")
    )

    private fun spear() = listOf(
        stage("矛 1", "抢夺3"),
        stage("矛 2", choice("锋利5", "节肢杀手5", "亡灵杀手5"), "火焰附加2"),
        stage("矛 3", "耐久3", "突进3", "击退2", "经验修补")
    )

    private fun fallback() = listOf(
        stage("通用 1", "耐久3", "经验修补")
    )

    private fun stage(name: String, vararg entries: Any): EnchantStage =
        EnchantStage(
            name,
            entries.mapNotNull {
                when (it) {
                    is String -> StageEntry.one(it)
                    is StageEntry -> it
                    else -> null
                }
            }
        )

    private fun choice(vararg labels: String): StageEntry = StageEntry.choice(*labels)
}
