package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.setting.impl.ItemSelectSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BarrierBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CommandBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import net.minecraft.resources.ResourceKey

class ANBlockESP : ANBaseModule(
    name = "BlockESP",
    description = "透视高亮选中的特定方块或非法方块",
    category = ANModuleCategory.RENDER,
    chineseName = "方块透视"
), ANWorldRenderModule {
    val selectedBlocks = addSetting(ANSetting("Blocks", ItemSelectSetting(ArrayList())))
    val range = addSetting(ANSetting("Range", 64, 4, 128))
    val scanDelay = addSetting(ANSetting("ScanDelay", 1000, 250, 5000))
    val limit = addSetting(ANSetting("Limit", true))
    val limitCount = addSetting(ANSetting("LimitCount", 128, 1, 2048))
    val illegals = addSetting(ANSetting("Illegals", true))
    val tracers = addSetting(ANSetting("Tracers", false))
    val fill = addSetting(ANSetting("Fill", true))
    val outline = addSetting(ANSetting("Outline", true))
    val color = addSetting(ANSetting("Color", ColorGroupSetting(Color(0x9900FFFF.toInt(), true).rgb)))

    @Volatile
    private var renderBlocks: List<BlockVec> = emptyList()
    @Volatile
    private var lastScanMs = 0L

    private val scanning = AtomicBoolean(false)
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ANBlockESP-Scanner").apply { isDaemon = true }
    }

    override fun onEnable() {
        renderBlocks = emptyList()
        lastScanMs = 0L
        scanning.set(false)
    }

    override fun onDisable() {
        renderBlocks = emptyList()
    }

    override fun onUnload() {
        scanExecutor.shutdownNow()
    }

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val now = System.currentTimeMillis()
        if (now - lastScanMs < scanDelay.value) return
        if (!scanning.compareAndSet(false, true)) return

        lastScanMs = now
        val snapshot = ScanSnapshot(
            playerX = player.x,
            playerY = player.y,
            playerZ = player.z,
            range = range.value,
            minY = level.minBuildHeight + 1,
            maxY = level.maxBuildHeight - 1,
            dimension = level.dimension(),
            selectedIds = selectedBlocks.value.getItemsById().toSet(),
            includeIllegals = illegals.value,
            limit = if (limit.value) limitCount.value.coerceAtLeast(1) else Int.MAX_VALUE
        )

        scanExecutor.execute {
            try {
                renderBlocks = scan(snapshot)
            } finally {
                scanning.set(false)
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!fill.value && !outline.value && !tracers.value) return
        val player = Minecraft.getInstance().player ?: return
        val blocks = renderBlocks
        if (blocks.isEmpty()) return

        val currentRangeSq = range.value.toDouble() * range.value.toDouble()
        val renderLimit = if (limit.value) limitCount.value else Int.MAX_VALUE
        val baseColor = color.value.toANColor()
        val lineColor = if (outline.value) baseColor.withAlpha(255) else baseColor.withAlpha(0)
        val fillColor = if (fill.value) baseColor else null
        val tracerColor = baseColor.withAlpha(255)
        val from = ANRender3DEngine.crosshairWorldPos(context)
        var drawn = 0

        for (block in blocks) {
            if (drawn >= renderLimit) break
            if (block.distanceSqTo(player.x, player.y, player.z) > currentRangeSq) continue
            if (tracers.value) {
                ANRender3DEngine.line(context, from, block.center(), tracerColor)
            }
            if (fill.value || outline.value) {
                ANRender3DEngine.box(context, block.box(), lineColor, fillColor)
            }
            drawn++
        }
    }

    private fun scan(snapshot: ScanSnapshot): List<BlockVec> {
        if (snapshot.selectedIds.isEmpty() && !snapshot.includeIllegals) return emptyList()

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return emptyList()
        val radius = snapshot.range
        val rangeSq = radius.toDouble() * radius.toDouble()
        val startX = floor(snapshot.playerX - radius).toInt()
        val endX = ceil(snapshot.playerX + radius).toInt()
        val startZ = floor(snapshot.playerZ - radius).toInt()
        val endZ = ceil(snapshot.playerZ + radius).toInt()
        val startChunkX = Math.floorDiv(startX, 16)
        val endChunkX = Math.floorDiv(endX, 16)
        val startChunkZ = Math.floorDiv(startZ, 16)
        val endChunkZ = Math.floorDiv(endZ, 16)
        val results = ArrayList<BlockVec>(snapshot.limit.coerceAtMost(512))
        val hardCap = if (snapshot.limit == Int.MAX_VALUE) 4096 else (snapshot.limit * 4).coerceAtLeast(256)
        val pos = BlockPos.MutableBlockPos()

        for (chunkX in startChunkX..endChunkX) {
            for (chunkZ in startChunkZ..endChunkZ) {
                val chunk = level.chunkSource.getChunk(chunkX, chunkZ, false) ?: continue
                val chunkStartX = maxOf(startX, chunkX * 16)
                val chunkEndX = minOf(endX, chunkX * 16 + 15)
                val chunkStartZ = maxOf(startZ, chunkZ * 16)
                val chunkEndZ = minOf(endZ, chunkZ * 16 + 15)

                for (x in chunkStartX..chunkEndX) {
                    val dx = x + 0.5 - snapshot.playerX
                    for (z in chunkStartZ..chunkEndZ) {
                        val dz = z + 0.5 - snapshot.playerZ
                        if (dx * dx + dz * dz > rangeSq) continue
                        for (y in snapshot.minY..snapshot.maxY) {
                            val dy = y + 0.5 - snapshot.playerY
                            val distanceSq = dx * dx + dy * dy + dz * dz
                            if (distanceSq > rangeSq) continue
                            pos.set(x, y, z)
                            val state = chunk.getBlockState(pos)
                            val block = state.block
                            if (state.isAir || !shouldAdd(block, y, snapshot)) continue
                            results += BlockVec(x, y, z, distanceSq)
                            if (results.size >= hardCap) {
                                return results.sortedBy { it.distanceSq }.take(snapshot.limit)
                            }
                        }
                    }
                }
            }
        }

        return results.sortedBy { it.distanceSq }.take(snapshot.limit)
    }

    private fun shouldAdd(block: Block, y: Int, snapshot: ScanSnapshot): Boolean {
        val id = block.descriptionId.removePrefix("block.minecraft.")
        if (snapshot.selectedIds.contains(id)) return true
        return snapshot.includeIllegals && isIllegal(block, y, snapshot.dimension)
    }

    private fun isIllegal(block: Block, y: Int, dimension: ResourceKey<Level>): Boolean {
        if (block is CommandBlock || block is BarrierBlock) return true
        if (block != Blocks.BEDROCK) return false
        return if (dimension == Level.NETHER) {
            y > 127 || (y in 5..122)
        } else {
            y > 4
        }
    }

    private data class ScanSnapshot(
        val playerX: Double,
        val playerY: Double,
        val playerZ: Double,
        val range: Int,
        val minY: Int,
        val maxY: Int,
        val dimension: ResourceKey<Level>,
        val selectedIds: Set<String>,
        val includeIllegals: Boolean,
        val limit: Int
    )

    private data class BlockVec(val x: Int, val y: Int, val z: Int, val distanceSq: Double) {
        fun box(): AABB = AABB(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)

        fun center(): Vec3 = Vec3(x + 0.5, y + 0.5, z + 0.5)

        fun distanceSqTo(playerX: Double, playerY: Double, playerZ: Double): Double {
            val dx = x + 0.5 - playerX
            val dy = y + 0.5 - playerY
            val dz = z + 0.5 - playerZ
            return dx * dx + dy * dy + dz * dz
        }
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())
}
