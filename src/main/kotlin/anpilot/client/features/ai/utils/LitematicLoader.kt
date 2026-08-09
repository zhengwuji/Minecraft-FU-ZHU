package anpilot.client.features.ai.utils

import anpilot.client.features.ai.utils.litematic.LitematicSectionMeshCache
import anpilot.client.renderer.ANColor
import com.mojang.blaze3d.vertex.PoseStack
import anpilot.client.compat.LevelRenderContext
import anpilot.client.compat.Identifier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.rendertype.ANPilotRenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.ModelManager
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation as BlockRotation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.properties.BlockStateProperties

object LitematicLoader {
    private val logger = LoggerFactory.getLogger("ANPilotLitematicLoader")
    private val textureMeshCache = LitematicSectionMeshCache()

    fun getProxyState(state: BlockState): BlockState {
        val blockName = BuiltInRegistries.BLOCK.getKey(state.block).path
        if (blockName == "chest" || blockName == "trapped_chest" || blockName == "ender_chest") {
            var proxy = Blocks.BARREL.defaultBlockState()
            val horizFacing = BlockStateProperties.HORIZONTAL_FACING
            val facing = BlockStateProperties.FACING
            if (state.hasProperty(horizFacing)) {
                val dir = state.getValue(horizFacing)
                if (proxy.hasProperty(facing)) {
                    proxy = proxy.setValue(facing, dir)
                }
            }
            return proxy
        }
        return state
    }

    fun load(file: File): Projection {
        if (!file.exists() || !file.isFile) return Projection(file.name, emptyList(), Bounds.EMPTY)
        return runCatching {
            if (file.extension.equals("litematic", ignoreCase = true)) {
                loadLitematic(file)
            } else {
                loadTextProjection(file)
            }
        }.getOrElse { exception ->
            logger.warn("Failed to load projection ${file.name}", exception)
            Projection(file.name, emptyList(), Bounds.EMPTY)
        }
    }

    fun render(context: LevelRenderContext, projection: Projection, transform: Transform, options: RenderOptions) {
        render(context, RenderCache.build(projection, transform), options)
    }

    fun render(context: LevelRenderContext, cache: RenderCache, options: RenderOptions) {
        if (cache.isEmpty || (!options.texture && !options.fill && !options.outline)) return
        val player = Minecraft.getInstance().player ?: return
        val level = Minecraft.getInstance().level
        val camera = context.levelState().cameraRenderState.pos
        val visibleSections = ArrayList<RenderSection>(cache.sections)

        if (visibleSections.isEmpty()) return
        if (level != null) {
            cache.refreshStatuses(level, visibleSections, options.statusChecksPerFrame)
        }

        if (options.texture) {
            textureMeshCache.render(context, cache, visibleSections, options.renderBuilt)
        }

        if (options.fill && isCustomGeometryWithinBudget(visibleSections, options, GeometryKind.FILL)) {
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), ANPilotRenderTypes.XRAY_FILLED_BOX) { _, vertexConsumer ->
                for (section in visibleSections) {
                    for (block in section.blocks) {
                        if (!block.shouldRender(options.renderBuilt)) continue
                        val color = options.colorFor(block.status) ?: continue
                        renderFilledBox(
                            vertexConsumer,
                            context.poseStack(),
                            block.pos,
                            camera,
                            color.red / 255f,
                            color.green / 255f,
                            color.blue / 255f,
                            options.fillAlpha
                        )
                    }
                }
            }
        }

        if (options.outline && isCustomGeometryWithinBudget(visibleSections, options, GeometryKind.OUTLINE)) {
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), ANPilotRenderTypes.XRAY_LINES) { _, vertexConsumer ->
                for (section in visibleSections) {
                    for (block in section.blocks) {
                        if (!block.shouldRender(options.renderBuilt)) continue
                        val color = options.colorFor(block.status) ?: continue
                        renderBoxOutline(
                            vertexConsumer,
                            context.poseStack(),
                            block.pos,
                            camera,
                            color.red / 255f,
                            color.green / 255f,
                            color.blue / 255f,
                            options.outlineAlpha
                        )
                    }
                }
            }
        }
    }

    private fun isCustomGeometryWithinBudget(
        sections: List<RenderSection>,
        options: RenderOptions,
        kind: GeometryKind
    ): Boolean {
        val multiplier = if (kind == GeometryKind.FILL) 24 else 24
        var candidateBlocks = 0
        for (section in sections) {
            for (block in section.blocks) {
                if (!block.shouldRender(options.renderBuilt)) continue
                if (options.colorFor(block.status) == null) continue
                candidateBlocks++
                if (candidateBlocks * multiplier > options.customGeometryVertexBudget) {
                    return false
                }
            }
        }
        return true
    }

    private enum class GeometryKind {
        FILL,
        OUTLINE
    }

    private fun renderFilledBox(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        poseStack: PoseStack,
        pos: BlockPos,
        camera: Vec3,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val minX = (pos.x - camera.x).toFloat()
        val minY = (pos.y - camera.y).toFloat()
        val minZ = (pos.z - camera.z).toFloat()
        val maxX = minX + 1.0f
        val maxY = minY + 1.0f
        val maxZ = minZ + 1.0f

        val pose = poseStack.last().pose()

        // Down
        consumer.vertex(pose, minX, minY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, minY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, minY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, minY, maxZ).color(r, g, b, a).endVertex()

        // Up
        consumer.vertex(pose, minX, maxY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, maxY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, maxY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, maxY, minZ).color(r, g, b, a).endVertex()

        // North
        consumer.vertex(pose, minX, minY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, maxY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, maxY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, minY, minZ).color(r, g, b, a).endVertex()

        // South
        consumer.vertex(pose, maxX, minY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, maxY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, maxY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, minY, maxZ).color(r, g, b, a).endVertex()

        // West
        consumer.vertex(pose, minX, minY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, maxY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, maxY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, minX, minY, minZ).color(r, g, b, a).endVertex()

        // East
        consumer.vertex(pose, maxX, minY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, maxY, minZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, maxY, maxZ).color(r, g, b, a).endVertex()
        consumer.vertex(pose, maxX, minY, maxZ).color(r, g, b, a).endVertex()
    }

    private fun renderBoxOutline(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        poseStack: PoseStack,
        pos: BlockPos,
        camera: Vec3,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val minX = (pos.x - camera.x).toFloat()
        val minY = (pos.y - camera.y).toFloat()
        val minZ = (pos.z - camera.z).toFloat()
        val maxX = minX + 1.0f
        val maxY = minY + 1.0f
        val maxZ = minZ + 1.0f

        val pose = poseStack.last().pose()
        val normal = poseStack.last().normal()

        fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, nx: Float, ny: Float, nz: Float) {
            consumer.vertex(pose, x1, y1, z1).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex()
            consumer.vertex(pose, x2, y2, z2).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex()
        }

        line(minX, minY, minZ, maxX, minY, minZ, 0f, -1f, 0f)
        line(maxX, minY, minZ, maxX, minY, maxZ, 1f, 0f, 0f)
        line(maxX, minY, maxZ, minX, minY, maxZ, 0f, 0f, 1f)
        line(minX, minY, maxZ, minX, minY, minZ, -1f, 0f, 0f)

        line(minX, maxY, minZ, maxX, maxY, minZ, 0f, 1f, 0f)
        line(maxX, maxY, minZ, maxX, maxY, maxZ, 1f, 0f, 0f)
        line(maxX, maxY, maxZ, minX, maxY, maxZ, 0f, 0f, 1f)
        line(minX, maxY, maxZ, minX, maxY, minZ, -1f, 0f, 0f)

        line(minX, minY, minZ, minX, maxY, minZ, -1f, 0f, 0f)
        line(maxX, minY, minZ, maxX, maxY, minZ, 1f, 0f, 0f)
        line(maxX, minY, maxZ, maxX, maxY, maxZ, 1f, 0f, 0f)
        line(minX, minY, maxZ, minX, maxY, maxZ, -1f, 0f, 0f)
    }

    private fun loadLitematic(file: File): Projection {
        val root = NbtIo.readCompressed(file)
        val name = file.nameWithoutExtension
        val regionsTag = root.getCompound("Regions")
        val blocks = ArrayList<StateBlock>()

        for (regionName in regionsTag.allKeys) {
            val region = regionsTag.getCompound(regionName)
            val size = region.getCompound("Size")
            val sizeX = size.getInt("x")
            val sizeY = size.getInt("y")
            val sizeZ = size.getInt("z")
            val absSizeX = abs(sizeX)
            val absSizeY = abs(sizeY)
            val absSizeZ = abs(sizeZ)

            val pos = region.getCompound("Position")
            val regPosX = pos.getInt("x")
            val regPosY = pos.getInt("y")
            val regPosZ = pos.getInt("z")

            val paletteTag = region.getList("BlockStatePalette", 10)
            val palette = Array(paletteTag.size) { index ->
                parseBlockState(paletteTag.getCompound(index))
            }

            val blockStates = region.getLongArray("BlockStates")
            val totalVolume = absSizeX * absSizeY * absSizeZ
            val bitsPerEntry = calculateBitsPerEntry(palette.size)

            for (i in 0 until totalVolume) {
                val paletteIndex = readPackedVal(blockStates, bitsPerEntry, i)
                if (paletteIndex < 0 || paletteIndex >= palette.size) continue

                val state = palette[paletteIndex]
                if (state.isAir) continue

                val lx = i % absSizeX
                val ly = (i / absSizeX) % absSizeY
                val lz = (i / (absSizeX * absSizeY)) % absSizeZ

                val relX = if (sizeX < 0) -lx else lx
                val relY = if (sizeY < 0) -ly else ly
                val relZ = if (sizeZ < 0) -lz else lz

                val finalPos = BlockPos(regPosX + relX, regPosY + relY, regPosZ + relZ)
                blocks.add(StateBlock(finalPos, state))
            }
        }

        val bounds = Bounds.from(blocks.map { it.pos })
        return Projection(name, blocks, bounds)
    }

    private fun loadTextProjection(file: File): Projection {
        val blocks = ArrayList<StateBlock>()
        file.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(";", limit = 4)
                if (parts.size < 4) continue
                val x = parts[0].toIntOrNull() ?: continue
                val y = parts[1].toIntOrNull() ?: continue
                val z = parts[2].toIntOrNull() ?: continue
                val state = parseBlockStateString(parts[3]) ?: continue
                if (state.isAir) continue
                blocks.add(StateBlock(BlockPos(x, y, z), state))
            }
        }
        val bounds = Bounds.from(blocks.map { it.pos })
        return Projection(file.nameWithoutExtension, blocks, bounds)
    }

    private fun parseBlockState(tag: CompoundTag): BlockState {
        val name = tag.getString("Name")
        val block = BuiltInRegistries.BLOCK.get(Identifier(name))
        var state = block.defaultBlockState()

        if (tag.contains("Properties")) {
            val props = tag.getCompound("Properties")
            for (key in props.allKeys) {
                val property = block.stateDefinition.getProperty(key) ?: continue
                val valueString = props.getString(key)
                state = setPropertyString(state, property, valueString)
            }
        }
        return state
    }

    private fun <T : Comparable<T>> setPropertyString(
        state: BlockState,
        property: net.minecraft.world.level.block.state.properties.Property<T>,
        valueString: String
    ): BlockState {
        val optionalValue = property.getValue(valueString)
        return if (optionalValue.isPresent) {
            state.setValue(property, optionalValue.get())
        } else {
            state
        }
    }

    private fun parseBlockStateString(blockStateStr: String): BlockState? {
        return runCatching {
            val result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), blockStateStr, true)
            result.blockState()
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Comparable<T>> setValueUnchecked(
        state: BlockState,
        property: net.minecraft.world.level.block.state.properties.Property<*>,
        value: Any
    ): BlockState {
        return state.setValue(property as net.minecraft.world.level.block.state.properties.Property<T>, value as T)
    }

    private fun calculateBitsPerEntry(paletteSize: Int): Int {
        var bits = 2
        while ((1 shl bits) < paletteSize) {
            bits++
        }
        return bits.coerceAtLeast(2)
    }

    private fun readPackedVal(longArray: LongArray, bitsPerEntry: Int, index: Int): Int {
        if (longArray.isEmpty()) return 0
        val bitIndex = index.toLong() * bitsPerEntry
        val startLong = (bitIndex / 64).toInt()
        val endLong = ((bitIndex + bitsPerEntry - 1) / 64).toInt()
        val startBit = (bitIndex % 64).toInt()

        if (startLong >= longArray.size) return 0

        return if (startLong == endLong) {
            ((longArray[startLong] ushr startBit) and ((1L shl bitsPerEntry) - 1)).toInt()
        } else {
            val bitsFromFirst = 64 - startBit
            val firstPart = (longArray[startLong] ushr startBit) and ((1L shl bitsFromFirst) - 1)
            val secondPart = if (endLong < longArray.size) {
                longArray[endLong] and ((1L shl (bitsPerEntry - bitsFromFirst)) - 1)
            } else 0L
            (firstPart or (secondPart shl bitsFromFirst)).toInt()
        }
    }

    data class StateBlock(val pos: BlockPos, val state: BlockState)

    data class Transform(
        val origin: BlockPos = BlockPos.ZERO,
        val rotation: BlockRotation = BlockRotation.NONE,
        val mirror: Boolean = false
    ) {
        fun apply(pos: BlockPos): BlockPos {
            var x = pos.x
            val y = pos.y
            var z = pos.z

            if (mirror) {
                x = -x
            }

            when (rotation) {
                BlockRotation.CLOCKWISE_90 -> {
                    val tx = x
                    x = -z
                    z = tx
                }
                BlockRotation.CLOCKWISE_180 -> {
                    x = -x
                    z = -z
                }
                BlockRotation.COUNTERCLOCKWISE_90 -> {
                    val tx = x
                    x = z
                    z = -tx
                }
                BlockRotation.NONE -> {}
            }

            return origin.offset(x, y, z)
        }

        fun applyState(state: BlockState): BlockState {
            var s = state
            if (mirror) {
                s = s.mirror(net.minecraft.world.level.block.Mirror.LEFT_RIGHT)
            }
            if (rotation != BlockRotation.NONE) {
                s = s.rotate(rotation)
            }
            return getProxyState(s)
        }

        companion object {
            val IDENTITY = Transform()
        }
    }

    data class Bounds(
        val min: BlockPos,
        val max: BlockPos
    ) {
        val isEmpty: Boolean get() = this == EMPTY

        val width: Int get() = if (isEmpty) 0 else max.x - min.x + 1
        val height: Int get() = if (isEmpty) 0 else max.y - min.y + 1
        val length: Int get() = if (isEmpty) 0 else max.z - min.z + 1

        fun contains(pos: BlockPos): Boolean =
            !isEmpty && pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z

        companion object {
            val EMPTY = Bounds(BlockPos.ZERO, BlockPos.ZERO)

            fun from(positions: Collection<BlockPos>): Bounds {
                if (positions.isEmpty()) return EMPTY
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var minZ = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var maxY = Int.MIN_VALUE
                var maxZ = Int.MIN_VALUE

                for (pos in positions) {
                    if (pos.x < minX) minX = pos.x
                    if (pos.y < minY) minY = pos.y
                    if (pos.z < minZ) minZ = pos.z
                    if (pos.x > maxX) maxX = pos.x
                    if (pos.y > maxY) maxY = pos.y
                    if (pos.z > maxZ) maxZ = pos.z
                }

                return Bounds(BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))
            }
        }
    }

    data class Projection(
        val name: String,
        val blocks: List<StateBlock>,
        val bounds: Bounds
    )

    enum class BlockStatus {
        CORRECT,
        WRONG,
        MISSING
    }

    data class RenderBlock(
        val pos: BlockPos,
        val state: BlockState,
        var status: BlockStatus
    ) {
        fun shouldRender(renderBuilt: Boolean): Boolean =
            renderBuilt || status != BlockStatus.CORRECT
    }

    data class RenderSection(
        val sectionPos: BlockPos,
        val bounds: AABB,
        val blocks: List<RenderBlock>
    ) {
        private var correctCount = 0

        fun updateCounts() {
            correctCount = blocks.count { it.status == BlockStatus.CORRECT }
        }

        fun isFullyCorrect(): Boolean = correctCount == blocks.size
    }

    class RenderCache private constructor(
        val transform: Transform,
        val sections: List<RenderSection>,
        val bounds: Bounds,
        val totalBlocks: Int
    ) {
        val isEmpty: Boolean get() = sections.isEmpty()

        fun refreshStatuses(level: net.minecraft.world.level.Level, sectionsToUpdate: List<RenderSection>, checksPerFrame: Int) {
            var checks = 0
            for (section in sectionsToUpdate) {
                for (block in section.blocks) {
                    if (checks >= checksPerFrame) {
                        section.updateCounts()
                        return
                    }
                    val worldState = level.getBlockState(block.pos)
                    val expectedState = block.state
                    block.status = when {
                        worldState == expectedState -> BlockStatus.CORRECT
                        worldState.isAir -> BlockStatus.MISSING
                        else -> BlockStatus.WRONG
                    }
                    checks++
                }
                section.updateCounts()
            }
        }

        companion object {
            fun build(projection: Projection, transform: Transform): RenderCache {
                if (projection.blocks.isEmpty()) {
                    return RenderCache(transform, emptyList(), Bounds.EMPTY, 0)
                }

                val sectionMap = HashMap<Long, MutableList<RenderBlock>>()
                val transformedPositions = ArrayList<BlockPos>(projection.blocks.size)

                for (block in projection.blocks) {
                    val finalPos = transform.apply(block.pos)
                    val finalState = transform.applyState(block.state)
                    transformedPositions.add(finalPos)

                    val secX = finalPos.x shr 4
                    val secY = finalPos.y shr 4
                    val secZ = finalPos.z shr 4
                    val secKey = (secX.toLong() and 0x3FFFFFL) or
                            ((secZ.toLong() and 0x3FFFFFL) shl 22) or
                            ((secY.toLong() and 0xFFFFFL) shl 44)

                    val renderBlock = RenderBlock(finalPos, finalState, BlockStatus.MISSING)
                    sectionMap.getOrPut(secKey) { ArrayList() }.add(renderBlock)
                }

                val renderSections = ArrayList<RenderSection>()
                for ((secKey, blocks) in sectionMap) {
                    val secX = (secKey and 0x3FFFFFL).toInt().let { if (it >= 0x200000) it - 0x400000 else it }
                    val secZ = ((secKey ushr 22) and 0x3FFFFFL).toInt().let { if (it >= 0x200000) it - 0x400000 else it }
                    val secY = ((secKey ushr 44) and 0xFFFFFL).toInt().let { if (it >= 0x80000) it - 0x100000 else it }

                    val minX = (secX shl 4).toDouble()
                    val minY = (secY shl 4).toDouble()
                    val minZ = (secZ shl 4).toDouble()
                    val aabb = AABB(minX, minY, minZ, minX + 16.0, minY + 16.0, minZ + 16.0)

                    renderSections.add(RenderSection(BlockPos(secX shl 4, secY shl 4, secZ shl 4), aabb, blocks))
                }

                val bounds = Bounds.from(transformedPositions)
                return RenderCache(transform, renderSections, bounds, projection.blocks.size)
            }
        }
    }

    data class RenderOptions(
        val fill: Boolean = true,
        val fillAlpha: Float = 0.25f,
        val outline: Boolean = true,
        val outlineAlpha: Float = 0.85f,
        val texture: Boolean = false,
        val renderBuilt: Boolean = false,
        val statusChecksPerFrame: Int = 256,
        val customGeometryVertexBudget: Int = 120000,
        val correctColor: ANColor = ANColor(0x33, 0xFF, 0x55, 0xFF),
        val wrongColor: ANColor = ANColor(0xFF, 0x33, 0x33, 0xFF),
        val missingColor: ANColor = ANColor(0x33, 0x88, 0xFF, 0xFF)
    ) {
        fun colorFor(status: BlockStatus): ANColor? = when (status) {
            BlockStatus.CORRECT -> if (renderBuilt) correctColor else null
            BlockStatus.WRONG -> wrongColor
            BlockStatus.MISSING -> missingColor
        }
    }
}
