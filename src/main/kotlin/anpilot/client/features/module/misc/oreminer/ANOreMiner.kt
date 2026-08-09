package anpilot.client.features.module.misc.oreminer

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANTickEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.event.impl.Render3DEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import net.minecraft.core.Direction

class ANOreMiner : ANBaseModule(
    name = "ANOreMiner",
    description = "使用种子来获取矿物位置，反服务反矿透并进行全自动挖掘",
    category = ANModuleCategory.MISC,
    chineseName = "种子矿透",
) {
    private val chunkRenderers = ConcurrentHashMap<Long, Map<ANOreConfig, MutableSet<Vec3>>>()
    private var worldSeed: Long? = null
    private var oreConfig: Map<ResourceKey<Biome>, List<ANOreConfig>>? = null
    private var lastDimension: ResourceKey<Level>? = null
    private var wasBaritoneActive = false
    private var scanCursor = 0
    val oreGoals = mutableListOf<BlockPos>()

    val seedInput = addSetting(ANSetting("Seed", "-7346913998703726680"))
    val horizontalRadius = addSetting(ANSetting("ChunkRadius", 5, 1, 10))
    val baritone = addSetting(ANSetting("BaritoneMiner", false))
    
    init {
        ANOres.values.forEach { addSetting(it.setting) }
    }

    override fun onEnable() {
        val s = seedInput.value
        try {
            worldSeed = s.toLong()
        } catch (e: Exception) {
            worldSeed = s.hashCode().toLong()
        }
        lastDimension = mc.level?.dimension()
        reload()
    }

    override fun onDisable() {
        chunkRenderers.clear()
        oreConfig = null
        if (wasBaritoneActive) {
            BaritoneHelper.cancel()
            wasBaritoneActive = false
        }
    }

    @ANEventHandler
    fun onTick(event: ANTickEvent) {
        val level = mc.level ?: return
        if (mc.player == null) return

        if (level.dimension() != lastDimension) {
            lastDimension = level.dimension()
            reload()
        }

        scanVisibleChunksIncremental()

        if (baritone.value && !wasBaritoneActive) {
            BaritoneHelper.configure()
        } else if (!baritone.value && wasBaritoneActive) {
            BaritoneHelper.cancel()
        }
        wasBaritoneActive = baritone.value

        if (baritone.value && BaritoneHelper.isPathing()) {
            oreGoals.clear()
            val chunkPos = mc.player!!.chunkPosition()
            val rangeVal = 4
            for (range in 0..rangeVal) {
                for (x in -range + chunkPos.x..range + chunkPos.x) {
                    oreGoals.addAll(addToBaritone(x, chunkPos.z + range - rangeVal))
                }
                for (x in -range + 1 + chunkPos.x..<range + chunkPos.x) {
                    oreGoals.addAll(addToBaritone(x, chunkPos.z - range + rangeVal + 1))
                }
            }
        }
    }

    private fun reload() {
        chunkRenderers.clear()
        val level = mc.level ?: return
        val dimension = when (level.dimension()) {
            Level.NETHER -> OreDimension.Nether
            Level.END -> OreDimension.End
            else -> OreDimension.Overworld
        }
        oreConfig = ANOreConfig.getRegistry(dimension)
        scanCursor = 0
    }

    private fun scanVisibleChunksIncremental() {
        val player = mc.player ?: return
        val chunkX = player.chunkPosition().x
        val chunkZ = player.chunkPosition().z
        val radius = horizontalRadius.value
        val spiral = buildSpiralOrder(radius)
        if (spiral.isEmpty()) return

        val batchSize = 3
        for (i in 0 until batchSize) {
            val idx = (scanCursor + i) % spiral.size
            val offset = spiral[idx]
            doMathOnChunk(ChunkPos(chunkX + offset.first, chunkZ + offset.second))
        }
        scanCursor = (scanCursor + batchSize) % spiral.size
    }

    private fun buildSpiralOrder(radius: Int): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        for (r in 0..radius) {
            for (x in -r..r) {
                list.add(Pair(x, r))
                if (r != 0) list.add(Pair(x, -r))
            }
            for (z in -r + 1..<r) {
                list.add(Pair(r, z))
                list.add(Pair(-r, z))
            }
        }
        return list.distinct()
    }

    private fun addToBaritone(chunkX: Int, chunkZ: Int): List<BlockPos> {
        val chunkKey = (chunkX.toLong() and 0xFFFFFFFFL) or (chunkZ.toLong() and 0xFFFFFFFFL shl 32)
        val chunkMap = chunkRenderers[chunkKey] ?: return emptyList()
        val list = mutableListOf<BlockPos>()

        for ((ore, positions) in chunkMap) {
            if (ore.active.value) {
                for (pos in positions) {
                    list.add(BlockPos(pos.x.toInt(), pos.y.toInt(), pos.z.toInt()))
                }
            }
        }
        return list
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val level = mc.level ?: return
        val packet = event.packet
        if (packet is ClientboundBlockUpdatePacket) {
            if (packet.blockState.isSolidRender(level, packet.pos)) return
            val chunkKey = (packet.pos.x shr 4).toLong() and 0xFFFFFFFFL or ((packet.pos.z shr 4).toLong() and 0xFFFFFFFFL shl 32)
            val chunkMap = chunkRenderers[chunkKey]
            if (chunkMap != null) {
                val vec = Vec3.atLowerCornerOf(packet.pos)
                for (positions in chunkMap.values) {
                    positions.remove(vec)
                }
            }
        }
        if (packet is ClientboundLevelChunkWithLightPacket) {
            doMathOnChunk(ChunkPos(packet.x, packet.z))
        }
    }

    @ANEventHandler
    fun onRender(event: Render3DEvent) {
        if (mc.player == null || oreConfig == null) return
        val chunkX = mc.player!!.chunkPosition().x
        val chunkZ = mc.player!!.chunkPosition().z
        val rangeVal = horizontalRadius.value
        
        for (range in 0..rangeVal) {
            for (x in -range + chunkX..range + chunkX) {
                renderChunk(x, chunkZ + range - rangeVal, event)
            }
            for (x in -range + 1 + chunkX..<range + chunkX) {
                renderChunk(x, chunkZ - range + rangeVal + 1, event)
            }
        }
    }

    private fun renderChunk(x: Int, z: Int, event: Render3DEvent) {
        val chunkKey = (x.toLong() and 0xFFFFFFFFL) or (z.toLong() and 0xFFFFFFFFL shl 32)
        val chunk = chunkRenderers[chunkKey] ?: return
        
        for ((ore, positions) in chunk) {
            if (ore.active.value) {
                val c = ANColor(
                    (ore.color shr 16 and 0xFF),
                    (ore.color shr 8 and 0xFF),
                    (ore.color and 0xFF),
                    (ore.color shr 24 and 0xFF)
                )
                for (pos in positions) {
                    val box = AABB(pos.x, pos.y, pos.z, pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)
                    ANRender3DEngine.box(event.context, box, c, c.withAlpha(40))
                }
            }
        }
    }

    private fun doMathOnChunk(chunkPos: ChunkPos) {
        val world = mc.level ?: return
        val config = oreConfig ?: return
        val seed = worldSeed ?: return
        
        val chunkKey = (chunkPos.x.toLong() and 0xFFFFFFFFL) or (chunkPos.z.toLong() and 0xFFFFFFFFL shl 32)
        if (chunkRenderers.containsKey(chunkKey)) return
        
        val chunk = world.getChunk(chunkPos.x, chunkPos.z) ?: return
        
        val biomes = mutableSetOf<ResourceKey<Biome>>()
        for (section in chunk.sections) {
            section.biomes.getAll { entry -> entry.unwrapKey().orElse(null)?.let { biomes.add(it) } }
        }
        
        val oreSet = mutableSetOf<ANOreConfig>()
        for (biome in biomes) {
            oreSet.addAll(getDefaultOres(biome, config))
        }
        if (oreSet.isEmpty()) {
            chunkRenderers[chunkKey] = emptyMap()
            return
        }

        val map = ConcurrentHashMap<ANOreConfig, MutableSet<Vec3>>()
        for (ore in oreSet) {
            val random = WorldgenRandom(net.minecraft.world.level.levelgen.XoroshiroRandomSource(0L))
            val decorationSeed = random.setDecorationSeed(seed, chunkPos.minBlockX, chunkPos.minBlockZ)
            val stepSeed = decorationSeed + ore.step
            
            val count = ore.count.sample(random)
            val placements = mutableSetOf<Vec3>()
            for (i in 0 until count) {
                random.setFeatureSeed(stepSeed, i, ore.index)
                
                if (random.nextFloat() >= 1f / ore.rarity) continue
                val blockPos = BlockPos(
                    chunkPos.minBlockX + random.nextInt(16),
                    sampleOreY(world, ore, random),
                    chunkPos.minBlockZ + random.nextInt(16)
                )

                if (ore.scattered) {
                    placements.addAll(generateHidden(world, random, blockPos, ore.size))
                } else {
                    placements.addAll(generateVein(world, random, blockPos, ore.size, ore.discardOnAirChance))
                }
            }
            if (placements.isNotEmpty()) {
                map[ore] = placements
            }
        }
        chunkRenderers[chunkKey] = map
    }

    private fun getDefaultOres(biomeKey: ResourceKey<Biome>, config: Map<ResourceKey<Biome>, List<ANOreConfig>>): List<ANOreConfig> {
        return config[biomeKey] ?: emptyList()
    }

    private fun generateVein(
        world: ClientLevel,
        random: WorldgenRandom,
        blockPos: BlockPos,
        size: Int,
        discardOnAir: Float
    ): List<Vec3> {
        val poses = mutableListOf<Vec3>()
        val f = random.nextFloat() * Math.PI.toFloat()
        val g = size.toFloat() / 8.0f
        val h = Mth.ceil((size.toFloat() / 16.0f + 1.0f) / 2.0f)

        val d = blockPos.x.toDouble() + 8.0 + Mth.sin(f).toDouble() * g.toDouble()
        val e = blockPos.x.toDouble() + 8.0 - Mth.sin(f).toDouble() * g.toDouble()
        val i = blockPos.z.toDouble() + 8.0 + Mth.cos(f).toDouble() * g.toDouble()
        val j = blockPos.z.toDouble() + 8.0 - Mth.cos(f).toDouble() * g.toDouble()

        val k = (blockPos.y + random.nextInt(3) - 2).toDouble()
        val l = (blockPos.y + random.nextInt(3) - 2).toDouble()

        val m = blockPos.x - Mth.ceil(g) - h
        val n = blockPos.y - 2 - h
        val o = blockPos.z - Mth.ceil(g) - h
        val p = 2 * (Mth.ceil(g) + h)
        val q = 2 * (2 + h)

        for (r in m..m + p) {
            for (s in o..o + p) {
                if (n <= world.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, r, s)) {
                    return doVeinMath(world, random, size, d, e, i, j, k, l, m, n, o, p, q, discardOnAir)
                }
            }
        }
        return poses
    }

    private fun doVeinMath(
        world: ClientLevel,
        random: WorldgenRandom,
        size: Int,
        d: Double, e: Double, i: Double, j: Double, k: Double, l: Double,
        m: Int, n: Int, o: Int, p: Int, q: Int,
        discardOnAir: Float
    ): List<Vec3> {
        val poses = mutableListOf<Vec3>()
        val bitSet = BitSet(p * q * p)
        val mutable = BlockPos.MutableBlockPos()

        val doubles = DoubleArray(size * 4)
        for (r in 0 until size) {
            val s = r.toDouble() / size.toDouble()
            val t = Mth.lerp(s, d, e)
            val u = Mth.lerp(s, k, l)
            val v = Mth.lerp(s, i, j)

            val w = random.nextDouble() * size.toDouble() / 16.0
            val x = ((Mth.sin(Math.PI.toFloat() * s.toFloat()) + 1.0f) * w + 1.0) / 2.0
            doubles[r * 4] = t
            doubles[r * 4 + 1] = u
            doubles[r * 4 + 2] = v
            doubles[r * 4 + 3] = x
        }

        for (r in 0 until size - 1) {
            if (doubles[r * 4 + 3] > 0.0) {
                for (s in r + 1 until size) {
                    if (doubles[s * 4 + 3] > 0.0) {
                        val t = doubles[r * 4] - doubles[s * 4]
                        val u = doubles[r * 4 + 1] - doubles[s * 4 + 1]
                        val v = doubles[r * 4 + 2] - doubles[s * 4 + 2]
                        val w = doubles[r * 4 + 3] - doubles[s * 4 + 3]
                        if (w * w > t * t + u * u + v * v) {
                            if (w > 0.0) {
                                doubles[s * 4 + 3] = -1.0
                            } else {
                                doubles[r * 4 + 3] = -1.0
                            }
                        }
                    }
                }
            }
        }

        for (r in 0 until size) {
            val s = doubles[r * 4 + 3]
            if (s >= 0.0) {
                val t = doubles[r * 4]
                val u = doubles[r * 4 + 1]
                val v = doubles[r * 4 + 2]

                val ab = max(Mth.floor(t - s), m)
                val ac = max(Mth.floor(u - s), n)
                val ad = max(Mth.floor(v - s), o)
                val ae = max(Mth.floor(t + s), ab)
                val af = max(Mth.floor(u + s), ac)
                val ag = max(Mth.floor(v + s), ad)

                for (ah in ab..ae) {
                    val ai = (ah.toDouble() + 0.5 - t) / s
                    if (ai * ai < 1.0) {
                        for (aj in ac..af) {
                            val ak = (aj.toDouble() + 0.5 - u) / s
                            if (ai * ai + ak * ak < 1.0) {
                                for (al in ad..ag) {
                                    val am = (al.toDouble() + 0.5 - v) / s
                                    if (ai * ai + ak * ak + am * am < 1.0) {
                                        val an = ah - m + (aj - n) * p + (al - o) * p * q
                                        if (!bitSet.get(an)) {
                                            bitSet.set(an)
                                            mutable.set(ah, aj, al)
                                            if (isValidY(world, aj) && world.getBlockState(mutable).isSolidRender(world, mutable)) {
                                                if (shouldPlace(world, mutable, discardOnAir, random)) {
                                                    poses.add(Vec3(ah.toDouble(), aj.toDouble(), al.toDouble()))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return poses
    }

    private fun generateHidden(world: ClientLevel, random: WorldgenRandom, blockPos: BlockPos, size: Int): List<Vec3> {
        val poses = mutableListOf<Vec3>()
        val i = random.nextInt(size + 1)
        for (j in 0 until i) {
            val sz = Math.min(j, 7)
            val x = randomCoord(random, sz) + blockPos.x
            val y = randomCoord(random, sz) + blockPos.y
            val z = randomCoord(random, sz) + blockPos.z
            val pos = BlockPos(x, y, z)
            if (isValidY(world, y) && world.getBlockState(pos).isSolidRender(world, pos)) {
                poses.add(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
            }
        }
        return poses
    }

    private fun randomCoord(random: WorldgenRandom, size: Int): Int {
        return Math.round((random.nextFloat() - random.nextFloat()) * size.toFloat())
    }

    private fun sampleOreY(world: ClientLevel, ore: ANOreConfig, random: WorldgenRandom): Int {
        val context = ore.heightContext
        if (context != null) {
            ore.heightProvider?.let { provider ->
                runCatching { provider.sample(random, context) }.getOrNull()?.let { return it }
            }
        }
        val minY = world.minBuildHeight
        val height = world.height
        return minY + random.nextInt(height)
    }

    private fun shouldPlace(world: ClientLevel, pos: BlockPos, discardOnAir: Float, random: WorldgenRandom): Boolean {
        if (discardOnAir <= 0f) return true
        if (discardOnAir >= 1f) return !isExposedToAir(world, pos)
        return random.nextFloat() >= discardOnAir || !isExposedToAir(world, pos)
    }

    private fun isExposedToAir(world: ClientLevel, pos: BlockPos): Boolean {
        for (dir in Direction.entries) {
            val p = pos.relative(dir)
            if (world.getBlockState(p).isAir) return true
        }
        return false
    }

    private fun isValidY(world: ClientLevel, y: Int): Boolean {
        return y >= world.minBuildHeight && y < world.minBuildHeight + world.height
    }
}
