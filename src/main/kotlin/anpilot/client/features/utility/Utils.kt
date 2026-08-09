package anpilot.client.features.utility

import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.data.worldgen.placement.OrePlacements
import net.minecraft.resources.ResourceKey
import net.minecraft.util.valueproviders.ConstantInt
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.FeatureSorter
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.WorldGenerationContext
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter
import net.minecraft.world.level.levelgen.presets.WorldPresets
import java.awt.Color
import net.minecraft.world.level.Level

class Ore(
    feature: PlacedFeature,
    val step: Int,
    val index: Int,
    val name: String = "Ore",
    val activeSetting: ANSetting<Boolean>? = null,
    val color: Int,
    val isNether: Boolean = false
) {
    var count: IntProvider = ConstantInt.of(1)
    var heightProvider: HeightProvider? = null
    var heightContext: WorldGenerationContext
    var rarity: Float = 1f
    var discardOnAirChance: Float = 0f
    var size: Int = 0
    var scattered: Boolean = false

    init {
        val level = Minecraft.getInstance().level
        val bottom = level?.minBuildHeight ?: -64
        val height = level?.height ?: 384
        val ctor = WorldGenerationContext::class.java.constructors.first()
        heightContext = ctor.newInstance(null, LevelHeightAccessor.create(bottom, height)) as WorldGenerationContext

        for (modifier in feature.placement()) {
            when (modifier) {
                is CountPlacement -> {
                    try {
                        val field = CountPlacement::class.java.declaredFields.firstOrNull { it.type == IntProvider::class.java }
                        field?.isAccessible = true
                        count = field?.get(modifier) as? IntProvider ?: count
                    } catch (e: Exception) {}
                }
                is HeightRangePlacement -> {
                    try {
                        val field = HeightRangePlacement::class.java.declaredFields.firstOrNull { it.type == HeightProvider::class.java }
                        field?.isAccessible = true
                        heightProvider = field?.get(modifier) as? HeightProvider
                    } catch (e: Exception) {}
                }
                is RarityFilter -> {
                    try {
                        val field = RarityFilter::class.java.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                        field?.isAccessible = true
                        rarity = (field?.get(modifier) as? Int ?: 1).toFloat()
                    } catch (e: Exception) {}
                }
            }
        }

        val config = feature.feature().value().config()
        if (config is OreConfiguration) {
            discardOnAirChance = config.discardChanceOnAirExposure
            size = config.size
        }

        if (feature.feature().value().feature() is ScatteredOreFeature) {
            scattered = true
        }
    }

    companion object {
        fun getRegistry(dimension: ResourceKey<Level> = Level.OVERWORLD): Map<ResourceKey<Biome>, List<Ore>> {
            try {
                val registry = VanillaRegistries.createLookup()
                val features = registry.lookupOrThrow(Registries.PLACED_FEATURE)
                val reg = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions()
                val levelStem = if (dimension == Level.NETHER) LevelStem.NETHER else LevelStem.OVERWORLD
                val dim = reg.get(levelStem)
                val biomes = dim?.generator()?.biomeSource?.possibleBiomes() ?: emptySet()
                val biomes1 = biomes.stream().toList()

                val indexer = FeatureSorter.buildFeaturesPerStep(
                    biomes1, { it.value().generationSettings.features() }, true
                )

                val featureToOre = HashMap<PlacedFeature, Ore>()
                
                fun registerOre(
                    oreKey: ResourceKey<PlacedFeature>, defaultStep: Int, name: String, color: Int, isNether: Boolean = false
                ) {
                    try {
                        val orePlacementHolder = features.get(oreKey)
                        if (orePlacementHolder.isEmpty) return
                        val orePlacement = orePlacementHolder.get().value()
                        
                        for (step in listOf(defaultStep, 6, 7)) {
                            if (step < indexer.size) {
                                val index = indexer[step].indexMapping().applyAsInt(orePlacement)
                                if (index >= 0) {
                                    featureToOre[orePlacement] = Ore(orePlacement, step, index, name, null, color, isNether)
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                val isNether = dimension == Level.NETHER

                if (!isNether) {
                    registerOre(OrePlacements.ORE_COAL_LOWER, 6, "Coal", Color(47, 44, 54).rgb, false)
                    registerOre(OrePlacements.ORE_COAL_UPPER, 6, "Coal", Color(47, 44, 54).rgb, false)
                    registerOre(OrePlacements.ORE_IRON_MIDDLE, 6, "Iron", Color(236, 173, 119).rgb, false)
                    registerOre(OrePlacements.ORE_IRON_SMALL, 6, "Iron", Color(236, 173, 119).rgb, false)
                    registerOre(OrePlacements.ORE_IRON_UPPER, 6, "Iron", Color(236, 173, 119).rgb, false)
                    registerOre(OrePlacements.ORE_GOLD, 6, "Gold", Color(247, 229, 30).rgb, false)
                    registerOre(OrePlacements.ORE_GOLD_LOWER, 6, "Gold", Color(247, 229, 30).rgb, false)
                    registerOre(OrePlacements.ORE_GOLD_EXTRA, 6, "Gold", Color(247, 229, 30).rgb, false)
                    registerOre(OrePlacements.ORE_REDSTONE, 6, "Redstone", Color(245, 7, 23).rgb, false)
                    registerOre(OrePlacements.ORE_REDSTONE_LOWER, 6, "Redstone", Color(245, 7, 23).rgb, false)
                    registerOre(OrePlacements.ORE_DIAMOND, 6, "Diamond", Color(33, 244, 255).rgb, false)
                    registerOre(OrePlacements.ORE_DIAMOND_BURIED, 6, "Diamond", Color(33, 244, 255).rgb, false)
                    registerOre(OrePlacements.ORE_DIAMOND_LARGE, 6, "Diamond", Color(33, 244, 255).rgb, false)
                    registerOre(OrePlacements.ORE_LAPIS, 6, "Lapis", Color(8, 26, 189).rgb, false)
                    registerOre(OrePlacements.ORE_LAPIS_BURIED, 6, "Lapis", Color(8, 26, 189).rgb, false)
                    registerOre(OrePlacements.ORE_COPPER, 6, "Copper", Color(239, 151, 0).rgb, false)
                    registerOre(OrePlacements.ORE_COPPER_LARGE, 6, "Copper", Color(239, 151, 0).rgb, false)
                    registerOre(OrePlacements.ORE_EMERALD, 6, "Emerald", Color(27, 209, 45).rgb, false)
                } else {
                    registerOre(OrePlacements.ORE_GOLD_NETHER, 7, "Gold", Color(247, 229, 30).rgb, true)
                    registerOre(OrePlacements.ORE_GOLD_DELTAS, 7, "Gold", Color(247, 229, 30).rgb, true)
                    registerOre(OrePlacements.ORE_QUARTZ_NETHER, 7, "Quartz", Color(205, 205, 205).rgb, true)
                    registerOre(OrePlacements.ORE_QUARTZ_DELTAS, 7, "Quartz", Color(205, 205, 205).rgb, true)
                    registerOre(OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, 7, "Debris", Color(209, 27, 245).rgb, true)
                    registerOre(OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, 7, "Debris", Color(209, 27, 245).rgb, true)
                }

                val biomeOreMap = HashMap<ResourceKey<Biome>, List<Ore>>()

                biomes1.forEach { biome ->
                    val list = ArrayList<Ore>()
                    biome.unwrapKey().ifPresent { key ->
                        biomeOreMap[key] = list
                    }
                    biome.value().generationSettings.features().stream()
                        .flatMap { it.stream() }
                        .map { it.value() }
                        .filter { featureToOre.containsKey(it) }
                        .forEach { feature -> list.add(featureToOre[feature]!!) }
                }
                return biomeOreMap
            } catch (e: Exception) {
                e.printStackTrace()
                return emptyMap()
            }
        }
    }
}
