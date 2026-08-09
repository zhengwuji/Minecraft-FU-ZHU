package anpilot.client.features.module.misc.oreminer

import anpilot.client.features.setting.ANSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.data.worldgen.placement.OrePlacements
import net.minecraft.resources.ResourceKey
import net.minecraft.util.valueproviders.ConstantInt
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FeatureSorter
import net.minecraft.world.level.chunk.ChunkGenerator
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

enum class OreDimension {
    Overworld, Nether, End
}

class ANOreSetting(
    val name: String,
    val defaultState: Boolean,
    val color: Int 
) {
    val setting = ANSetting(name, defaultState)
}

object ANOres {
    val COAL = ANOreSetting("Coal", false, 0xFF2F2C36.toInt())
    val IRON = ANOreSetting("Iron", false, 0xFFECAD77.toInt())
    val GOLD = ANOreSetting("Gold", false, 0xFFF7E51E.toInt())
    val REDSTONE = ANOreSetting("Redstone", false, 0xFFF50717.toInt())
    val DIAMOND = ANOreSetting("Diamond", true, 0xFF21F4FF.toInt())
    val LAPIS = ANOreSetting("Lapis", false, 0xFF081ABD.toInt())
    val COPPER = ANOreSetting("Copper", false, 0xFFEF9700.toInt())
    val EMERALD = ANOreSetting("Emerald", false, 0xFF1BD12D.toInt())
    val QUARTZ = ANOreSetting("Quartz", false, 0xFFCDCDCD.toInt())
    val DEBRIS = ANOreSetting("Ancient Debris", true, 0xFFD11BF5.toInt())

    val values = listOf(COAL, IRON, GOLD, REDSTONE, DIAMOND, LAPIS, COPPER, EMERALD, QUARTZ, DEBRIS)
}

class ANOreConfig(
    val feature: PlacedFeature?,
    val step: Int,
    val index: Int,
    val active: ANSetting<Boolean>,
    val color: Int,
    generator: ChunkGenerator?,
    manualCount: IntProvider = ConstantInt.of(1),
    manualSize: Int = 0,
    manualRarity: Float = 1f,
    manualScattered: Boolean = false
) {
    var count: IntProvider = manualCount
    var heightProvider: HeightProvider? = null
    var heightContext: WorldGenerationContext? = null
    var rarity: Float = manualRarity
    var discardOnAirChance: Float = 0f
    var size: Int = manualSize
    var scattered: Boolean = manualScattered

    init {
        val bottom = Minecraft.getInstance().level?.minBuildHeight ?: -64
        val height = Minecraft.getInstance().level?.height
            ?: Minecraft.getInstance().level?.dimensionType()?.logicalHeight()
            ?: 384
        this.heightContext = createHeightContext(generator, bottom, height)

        val placementFeature = feature
        if (placementFeature != null) {
            for (modifier in placementFeature.placement()) {
                if (modifier is CountPlacement) {
                    count = getFieldValue(modifier, IntProvider::class.java) as? IntProvider ?: count
                } else if (modifier is HeightRangePlacement) {
                    heightProvider = getFieldValue(modifier, HeightProvider::class.java) as? HeightProvider
                } else if (modifier is RarityFilter) {
                    val r = getFieldValue(modifier, Int::class.javaPrimitiveType!!) as? Int ?: 1
                    rarity = r.toFloat()
                }
            }

            val featureConfig = placementFeature.feature().value().config()
            if (featureConfig is OreConfiguration) {
                this.discardOnAirChance = featureConfig.discardChanceOnAirExposure
                this.size = featureConfig.size
            }

            if (placementFeature.feature().value().feature() is ScatteredOreFeature) {
                this.scattered = true
            }
        }
    }

    private fun getFieldValue(obj: Any, type: Class<*>): Any? {
        val field = obj.javaClass.declaredFields.firstOrNull { type.isAssignableFrom(it.type) || it.type == type } ?: return null
        field.isAccessible = true
        return field.get(obj)
    }

    companion object {
        var lastError: String? = null
            private set
        var lastSource: String = "none"
            private set

        private fun createHeightContext(generator: ChunkGenerator?, bottom: Int, height: Int): WorldGenerationContext? {
            return if (generator != null) {
                runCatching {
                    WorldGenerationContext(generator, LevelHeightAccessor.create(bottom, height))
                }.getOrNull()
            } else runCatching {
                val ctor = WorldGenerationContext::class.java.constructors.first()
                ctor.newInstance(null, LevelHeightAccessor.create(bottom, height)) as WorldGenerationContext
            }.getOrNull()
        }

        fun getRegistry(dimension: OreDimension): Map<ResourceKey<Biome>, List<ANOreConfig>> {
            lastError = null
            lastSource = "none"

            val vanillaRegistry = runCatching { VanillaRegistries.createLookup() }
                .onFailure { lastError = "vanilla-lookup:${it.javaClass.simpleName}:${it.message}" }
                .getOrNull()
            vanillaRegistry?.let { registry ->
                val result = runCatching { getRegistry(dimension, registry) }
                    .onFailure { lastError = "vanilla:${it.javaClass.simpleName}:${it.message}" }
                    .getOrNull()
                if (!result.isNullOrEmpty() && result.values.sumOf { it.size } > 0) {
                    lastError = null
                    lastSource = "vanilla"
                    return result
                }
            }

            val currentWorldRegistry: HolderLookup.Provider? = Minecraft.getInstance().level?.registryAccess()
            currentWorldRegistry?.let { provider ->
                val worldResult = runCatching { getRegistryFromWorld(dimension, provider) }
                    .onFailure { lastError = "world-direct:${it.javaClass.simpleName}:${it.message}" }
                    .getOrNull()
                if (!worldResult.isNullOrEmpty() && worldResult.values.sumOf { it.size } > 0) {
                    lastError = null
                    lastSource = "world-direct"
                    return worldResult
                }
                if (lastError == null) {
                    lastError = "world-direct:empty"
                }
            }

            manualFallback(dimension).takeIf { it.values.sumOf { ores -> ores.size } > 0 }?.let {
                lastError = null
                lastSource = "manual-fallback"
                return it
            }
            return emptyMap()
        }

        private fun manualFallback(dimension: OreDimension): Map<ResourceKey<Biome>, List<ANOreConfig>> {
            val ores = when (dimension) {
                OreDimension.Nether -> listOf(
                    ANOreConfig(null, 7, 0, ANOres.GOLD.setting, ANOres.GOLD.color, null, ConstantInt.of(10), 10),
                    ANOreConfig(null, 7, 1, ANOres.DEBRIS.setting, ANOres.DEBRIS.color, null, ConstantInt.of(1), 3),
                    ANOreConfig(null, 7, 2, ANOres.DEBRIS.setting, ANOres.DEBRIS.color, null, ConstantInt.of(2), 2)
                )
                OreDimension.Overworld -> listOf(
                    ANOreConfig(null, 6, 0, ANOres.DIAMOND.setting, ANOres.DIAMOND.color, null, ConstantInt.of(7), 8),
                    ANOreConfig(null, 6, 1, ANOres.REDSTONE.setting, ANOres.REDSTONE.color, null, ConstantInt.of(8), 8),
                    ANOreConfig(null, 6, 2, ANOres.GOLD.setting, ANOres.GOLD.color, null, ConstantInt.of(4), 9),
                    ANOreConfig(null, 6, 3, ANOres.IRON.setting, ANOres.IRON.color, null, ConstantInt.of(10), 9),
                    ANOreConfig(null, 6, 4, ANOres.COAL.setting, ANOres.COAL.color, null, ConstantInt.of(20), 17)
                )
                OreDimension.End -> emptyList()
            }
            if (ores.isEmpty()) return emptyMap()

            val biomeKeys = Minecraft.getInstance().level?.registryAccess()
                ?.lookupOrThrow(Registries.BIOME)
                ?.listElementIds()
                ?.toList()
                .orEmpty()
                .ifEmpty {
                    if (dimension == OreDimension.Nether) listOf(Biomes.NETHER_WASTES) else emptyList()
                }
            return biomeKeys.associateWith { ores }
        }

        private fun getRegistryFromWorld(dimension: OreDimension, registry: HolderLookup.Provider): Map<ResourceKey<Biome>, List<ANOreConfig>> {
            val features = registry.lookupOrThrow(Registries.PLACED_FEATURE)
            val biomes = registry.lookupOrThrow(Registries.BIOME).listElements().toList()
            if (biomes.isEmpty()) return emptyMap()

            val candidates = mutableListOf<Triple<PlacedFeature, Int, ANOreSetting>>()
            registerByDimension(dimension) { key, step, setting ->
                val placement = features.get(key).orElse(null)?.value() ?: return@registerByDimension
                candidates.add(Triple(placement, step, setting))
            }
            if (candidates.isEmpty()) {
                lastError = "world-direct:no registered placed features"
                return emptyMap()
            }

            val candidateFeatures = candidates.mapTo(mutableSetOf()) { it.first }
            val dimensionBiomes = biomes.filter { biome ->
                biome.value().generationSettings.features().any { holderSet ->
                    holderSet.any { holder -> holder.value() in candidateFeatures }
                }
            }
            if (dimensionBiomes.isEmpty()) {
                lastError = "world-direct:no dimension biomes"
                return emptyMap()
            }

            val indexer = FeatureSorter.buildFeaturesPerStep(dimensionBiomes, { it.value().generationSettings.features() }, true)
            val featureToOre = mutableMapOf<PlacedFeature, ANOreConfig>()

            for ((placement, step, setting) in candidates) {
                if (step >= indexer.size) continue
                val index = indexer[step].indexMapping().applyAsInt(placement)
                if (index < 0) continue
                featureToOre[placement] = ANOreConfig(placement, step, index, setting.setting, setting.color, null)
            }

            if (featureToOre.isEmpty()) {
                lastError = "world-direct:no indexed placed features"
            }
            return biomeOreMap(dimensionBiomes, featureToOre)
        }

        private fun getRegistry(dimension: OreDimension, registry: HolderLookup.Provider): Map<ResourceKey<Biome>, List<ANOreConfig>> {
            val features = registry.lookupOrThrow(Registries.PLACED_FEATURE)
            val reg = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions()

            val dim = when (dimension) {
                OreDimension.Overworld -> reg.get(LevelStem.OVERWORLD)
                OreDimension.Nether -> reg.get(LevelStem.NETHER)
                OreDimension.End -> reg.get(LevelStem.END)
            } ?: return emptyMap()

            val biomes = dim.generator().biomeSource.possibleBiomes().toList()

            val indexer = FeatureSorter.buildFeaturesPerStep(biomes, { it.value().generationSettings.features() }, true)

            val featureToOre = mutableMapOf<PlacedFeature, ANOreConfig>()

            fun register(key: ResourceKey<PlacedFeature>, step: Int, setting: ANOreSetting) {
                val placement = features.getOrThrow(key).value()
                val index = indexer[step].indexMapping().applyAsInt(placement)
                featureToOre[placement] = ANOreConfig(placement, step, index, setting.setting, setting.color, dim.generator())
            }

            registerByDimension(dimension, ::register)
            return biomeOreMap(biomes, featureToOre)
        }

        private fun registerByDimension(dimension: OreDimension, register: (ResourceKey<PlacedFeature>, Int, ANOreSetting) -> Unit) {
            if (dimension == OreDimension.Nether) {
                register(OrePlacements.ORE_GOLD_NETHER, 7, ANOres.GOLD)
                register(OrePlacements.ORE_GOLD_DELTAS, 7, ANOres.GOLD)
                register(OrePlacements.ORE_QUARTZ_NETHER, 7, ANOres.QUARTZ)
                register(OrePlacements.ORE_QUARTZ_DELTAS, 7, ANOres.QUARTZ)
                register(OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, 7, ANOres.DEBRIS)
                register(OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, 7, ANOres.DEBRIS)
                return
            }

            if (dimension == OreDimension.End) return

            register(OrePlacements.ORE_COAL_LOWER, 6, ANOres.COAL)
            register(OrePlacements.ORE_COAL_UPPER, 6, ANOres.COAL)
            register(OrePlacements.ORE_IRON_MIDDLE, 6, ANOres.IRON)
            register(OrePlacements.ORE_IRON_SMALL, 6, ANOres.IRON)
            register(OrePlacements.ORE_IRON_UPPER, 6, ANOres.IRON)
            register(OrePlacements.ORE_GOLD, 6, ANOres.GOLD)
            register(OrePlacements.ORE_GOLD_LOWER, 6, ANOres.GOLD)
            register(OrePlacements.ORE_GOLD_EXTRA, 6, ANOres.GOLD)
            register(OrePlacements.ORE_REDSTONE, 6, ANOres.REDSTONE)
            register(OrePlacements.ORE_REDSTONE_LOWER, 6, ANOres.REDSTONE)
            register(OrePlacements.ORE_DIAMOND, 6, ANOres.DIAMOND)
            register(OrePlacements.ORE_DIAMOND_BURIED, 6, ANOres.DIAMOND)
            register(OrePlacements.ORE_DIAMOND_LARGE, 6, ANOres.DIAMOND)
            register(OrePlacements.ORE_LAPIS, 6, ANOres.LAPIS)
            register(OrePlacements.ORE_LAPIS_BURIED, 6, ANOres.LAPIS)
            register(OrePlacements.ORE_COPPER, 6, ANOres.COPPER)
            register(OrePlacements.ORE_COPPER_LARGE, 6, ANOres.COPPER)
            register(OrePlacements.ORE_EMERALD, 6, ANOres.EMERALD)
        }

        private fun biomeOreMap(biomes: List<net.minecraft.core.Holder<Biome>>, featureToOre: Map<PlacedFeature, ANOreConfig>): Map<ResourceKey<Biome>, List<ANOreConfig>> {
            val biomeOreMap = mutableMapOf<ResourceKey<Biome>, List<ANOreConfig>>()
            for (biome in biomes) {
                val list = mutableListOf<ANOreConfig>()
                biome.value().generationSettings.features().forEach { holderSet ->
                    holderSet.forEach { holder ->
                        val feat = holder.value()
                        if (featureToOre.containsKey(feat)) {
                            list.add(featureToOre[feat]!!)
                        }
                    }
                }
                biome.unwrapKey().ifPresent { biomeOreMap[it] = list }
            }
            return biomeOreMap
        }
    }
}
