package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class ANTunnelESP : ANBaseModule(
    name = "TunnelESP",
    description = "透视高亮地下1x1与1x2玩家人工挖矿隧道的廊道方块结构",
    category = ANModuleCategory.RENDER,
    chineseName = "隧道透视"
), ANWorldRenderModule {
    val range = addSetting(ANSetting("Range", 64, 16, 128))
    val yRange = addSetting(ANSetting("YRange", 48, 8, 128))
    val scanDelay = addSetting(ANSetting("ScanDelay", 2000, 500, 10000))
    val minLength = addSetting(ANSetting("MinLength", 5, 1, 32))
    val limitCount = addSetting(ANSetting("LimitCount", 96, 1, 512))
    val box = addSetting(ANSetting("Box", true))
    val outline = addSetting(ANSetting("Outline", true))
    val color = addSetting(ANSetting("Color", ColorGroupSetting(Color(0xAE8A8AF6.toInt(), true).rgb)))

    @Volatile
    private var renderBoxes: List<TunnelBox> = emptyList()
    @Volatile
    private var lastScanMs = 0L

    private val scanning = AtomicBoolean(false)
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ANTunnelESP-Scanner").apply { isDaemon = true }
    }

    override fun onEnable() {
        renderBoxes = emptyList()
        lastScanMs = 0L
        scanning.set(false)
    }

    override fun onDisable() {
        renderBoxes = emptyList()
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
        val snapshot = TunnelSnapshot(
            playerX = player.x,
            playerY = player.y,
            playerZ = player.z,
            range = range.value,
            minY = max(level.minBuildHeight + 1, floor(player.y - yRange.value).toInt()),
            maxY = minOf(level.maxBuildHeight - 3, ceil(player.y + yRange.value).toInt()),
            minLength = minLength.value,
            limit = limitCount.value
        )

        scanExecutor.execute {
            try {
                renderBoxes = scan(snapshot)
            } finally {
                scanning.set(false)
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!box.value && !outline.value) return
        val boxes = renderBoxes
        if (boxes.isEmpty()) return

        val baseColor = color.value.toANColor()
        val lineColor = if (outline.value) baseColor.withAlpha(255) else baseColor.withAlpha(0)
        val fillColor = if (box.value) baseColor else null
        for (tunnel in boxes.take(limitCount.value)) {
            ANRender3DEngine.box(context, tunnel.box, lineColor, fillColor)
        }
    }

    private fun scan(snapshot: TunnelSnapshot): List<TunnelBox> {
        val level = Minecraft.getInstance().level ?: return emptyList()
        if (snapshot.minY > snapshot.maxY) return emptyList()

        val startX = floor(snapshot.playerX - snapshot.range).toInt()
        val endX = ceil(snapshot.playerX + snapshot.range).toInt()
        val startZ = floor(snapshot.playerZ - snapshot.range).toInt()
        val endZ = ceil(snapshot.playerZ + snapshot.range).toInt()
        val rangeSq = snapshot.range.toDouble() * snapshot.range.toDouble()
        val visited = HashSet<Long>()
        val boxes = ArrayList<TunnelBox>(snapshot.limit)
        val pos = BlockPos.MutableBlockPos()

        for (x in startX..endX) {
            val dx = x + 0.5 - snapshot.playerX
            for (z in startZ..endZ) {
                val dz = z + 0.5 - snapshot.playerZ
                if (dx * dx + dz * dz > rangeSq) continue
                for (y in snapshot.minY..snapshot.maxY) {
                    val key = BlockPos.asLong(x, y, z)
                    if (visited.contains(key)) continue
                    pos.set(x, y, z)
                    val shape = tunnelShape(pos) ?: continue
                    val tunnel = expandTunnel(pos, shape, snapshot, visited)
                    if (tunnel.length >= snapshot.minLength) {
                        boxes += tunnel
                        if (boxes.size >= snapshot.limit) return boxes
                    }
                }
            }
        }

        return boxes
    }

    private fun expandTunnel(start: BlockPos, shape: TunnelShape, snapshot: TunnelSnapshot, visited: MutableSet<Long>): TunnelBox {
        val mutable = BlockPos.MutableBlockPos(start.x, start.y, start.z)
        var negative = 0
        while (true) {
            negative++
            if (!setAlongAxis(mutable, start, shape.axis, -negative)) {
                negative--
                break
            }
            if (!insideScan(mutable, snapshot) || tunnelShape(mutable) != shape) {
                negative--
                break
            }
        }

        var positive = 0
        while (true) {
            positive++
            if (!setAlongAxis(mutable, start, shape.axis, positive)) {
                positive--
                break
            }
            if (!insideScan(mutable, snapshot) || tunnelShape(mutable) != shape) {
                positive--
                break
            }
        }

        for (offset in -negative..positive) {
            setAlongAxis(mutable, start, shape.axis, offset)
            visited += BlockPos.asLong(mutable.x, mutable.y, mutable.z)
        }

        val minX = if (shape.axis == Axis.X) start.x - negative else start.x
        val maxX = if (shape.axis == Axis.X) start.x + positive + 1 else start.x + 1
        val minZ = if (shape.axis == Axis.Z) start.z - negative else start.z
        val maxZ = if (shape.axis == Axis.Z) start.z + positive + 1 else start.z + 1
        val length = (negative + positive + 1).toDouble()
        return TunnelBox(
            AABB(minX.toDouble(), start.y.toDouble(), minZ.toDouble(), maxX.toDouble(), start.y + shape.height.toDouble(), maxZ.toDouble()),
            length
        )
    }

    private fun setAlongAxis(target: BlockPos.MutableBlockPos, origin: BlockPos, axis: Axis, offset: Int): Boolean {
        return when (axis) {
            Axis.X -> {
                target.set(origin.x + offset, origin.y, origin.z)
                true
            }
            Axis.Z -> {
                target.set(origin.x, origin.y, origin.z + offset)
                true
            }
        }
    }

    private fun insideScan(pos: BlockPos, snapshot: TunnelSnapshot): Boolean {
        val dx = pos.x + 0.5 - snapshot.playerX
        val dz = pos.z + 0.5 - snapshot.playerZ
        return pos.y in snapshot.minY..snapshot.maxY && dx * dx + dz * dz <= snapshot.range.toDouble() * snapshot.range.toDouble()
    }

    private fun tunnelShape(pos: BlockPos): TunnelShape? {
        return oneByTwo(pos) ?: oneByOne(pos)
    }

    private fun oneByTwo(pos: BlockPos): TunnelShape? {
        if (!isAir(pos) || !isAir(pos.above())) return null
        if (isAir(pos.below()) || isAir(pos.above(2))) return null

        if (isAir(pos.north()) && isAir(pos.south()) && isAir(pos.above().north()) && isAir(pos.above().south()) &&
            isSolid(pos.east()) && isSolid(pos.west()) && isSolid(pos.above().east()) && isSolid(pos.above().west())
        ) {
            return TunnelShape(Axis.Z, 2)
        }

        if (isAir(pos.east()) && isAir(pos.west()) && isAir(pos.above().east()) && isAir(pos.above().west()) &&
            isSolid(pos.north()) && isSolid(pos.south()) && isSolid(pos.above().north()) && isSolid(pos.above().south())
        ) {
            return TunnelShape(Axis.X, 2)
        }

        return null
    }

    private fun oneByOne(pos: BlockPos): TunnelShape? {
        if (!isAir(pos)) return null
        if (isAir(pos.below()) || isAir(pos.above())) return null

        if (isAir(pos.north()) && isAir(pos.south()) && isSolid(pos.east()) && isSolid(pos.west()) &&
            isSolid(pos.above().east()) && isSolid(pos.above().west())
        ) {
            return TunnelShape(Axis.Z, 1)
        }

        if (isAir(pos.east()) && isAir(pos.west()) && isSolid(pos.north()) && isSolid(pos.south())) {
            return TunnelShape(Axis.X, 1)
        }

        return null
    }

    private fun isAir(pos: BlockPos): Boolean {
        return Minecraft.getInstance().level?.getBlockState(pos)?.isAir == true
    }

    private fun isSolid(pos: BlockPos): Boolean = !isAir(pos)

    private data class TunnelSnapshot(
        val playerX: Double,
        val playerY: Double,
        val playerZ: Double,
        val range: Int,
        val minY: Int,
        val maxY: Int,
        val minLength: Int,
        val limit: Int
    )

    private data class TunnelShape(val axis: Axis, val height: Int)

    private data class TunnelBox(val box: AABB, val length: Double)

    private enum class Axis { X, Z }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())
}
