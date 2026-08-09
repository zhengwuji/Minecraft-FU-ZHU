package anpilot.client.features.module.misc

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.ai.utils.LitematicLoader
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.event.impl.Render2DEvent
import anpilot.client.features.manager.ANConfigManager
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.module.hud.HudColors
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.setting.impl.FileSelectSetting
import anpilot.client.minecraft.gui.MinecraftGuiRenderContext
import anpilot.client.renderer.ANColor
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.item.BlockItem
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import java.awt.Color

class ANFastBuild : ANBaseModule(
    name = "FastBuild",
    description = "快速自动摆放投影方块",
    category = ANModuleCategory.MISC,
    chineseName = "快速建筑"
), ANWorldRenderModule {
    val page = addSetting(ANSetting("Page", Page.MAIN))

    val file = addSetting(ANSetting("File", FileSelectSetting(ANConfigManager::fastBuildFileNames)) { isPage(Page.MAIN) })
    val layerBuild = addSetting(ANSetting("LayerBuild", 0, 0, 5) { isPage(Page.MAIN) })

    val placeRange = addSetting(ANSetting("PlaceRange", 4.0f, 1.0f, 6.0f) { isPage(Page.MAIN) })
    val placeDelay = addSetting(ANSetting("PlaceDelay", 1, 0, 5) { isPage(Page.MAIN) })
    val blocksPerTick = addSetting(ANSetting("BlocksPerTick", 2, 1, 16) { isPage(Page.MAIN) })
    val inventorySwap = addSetting(ANSetting("InventorySwap", true) { isPage(Page.MAIN) })
    val onlyBelowFeet = addSetting(ANSetting("OnlyBelowFeet", true) { isPage(Page.MAIN) })

    val offsetX = addSetting(ANSetting("OffsetX", 0, -64, 64) { isPage(Page.RENDER) })
    val offsetY = addSetting(ANSetting("OffsetY", 0, -32, 32) { isPage(Page.RENDER) })
    val offsetZ = addSetting(ANSetting("OffsetZ", 0, -64, 64) { isPage(Page.RENDER) })
    val rotate = addSetting(ANSetting("Rotate", 0, 0, 3) { isPage(Page.RENDER) })
    val fill = addSetting(ANSetting("Fill", false) { isPage(Page.RENDER) })
    val outline = addSetting(ANSetting("Outline", true) { isPage(Page.RENDER) })
    val missingColor = addSetting(ANSetting("MissingColor", ColorGroupSetting(Color(70, 170, 255, 70).rgb)) { isPage(Page.RENDER) })
    val wrongColor = addSetting(ANSetting("WrongColor", ColorGroupSetting(Color(255, 80, 80, 90).rgb)) { isPage(Page.RENDER) })
    val builtColor = addSetting(ANSetting("BuiltColor", ColorGroupSetting(Color(80, 255, 120, 45).rgb)) { isPage(Page.RENDER) })

    enum class Page {
        RENDER,
        MAIN,
    }

    private fun isPage(target: Page): Boolean = page.value == target

    private var rawProjection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
    private var projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
    private var placeBlocks = emptyList<BlockPlacer.PlaceBlock>()
    private var loadedFileName = ""
    private var origin = BlockPos.ZERO
    private var building = false

    private val schematicBlockMap = HashMap<BlockPos, BlockState>()

    private var minWorldY = 0
    private var maxWorldY = 0
    private var currentBuildLayer = 0

    private var hudBuiltCount = 0

    private val placerContext = object : BlockPlacer.Context {
        override val placeRangeSqr: Float get() = placeRange.getPow2Value()
        override val blocksPerTick: Int get() = this@ANFastBuild.blocksPerTick.value
        override val placeDelay: Int get() = this@ANFastBuild.placeDelay.value
        override val inventorySwap: Boolean get() = this@ANFastBuild.inventorySwap.value
        override val onlyBelowFeet: Boolean get() = this@ANFastBuild.onlyBelowFeet.value
        override val ignoreRedstoneOrientation: Boolean get() = false
        override val placeBlocks: List<BlockPlacer.PlaceBlock> get() = this@ANFastBuild.placeBlocks
        override val schematicBlockMap: Map<BlockPos, BlockState> get() = this@ANFastBuild.schematicBlockMap

        override fun shouldSkipBlock(state: BlockState) = shouldSkipProjectionBlock(state)
        override fun onBlockPlaced(pos: BlockPos) {
        }
    }
    private val placer = BlockPlacer(placerContext)

    override fun onEnable() {
        if (fullNullCheck()) {
            disable()
            return
        }
        building = false
        placer.resetCooldown()
        currentBuildLayer = 0
        loadProjection()
    }

    override fun onDisable() {
        building = false
        projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
        rawProjection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
        placeBlocks = emptyList()
        placer.resetCooldown()
        Inventory.endSwap()
        Inventory.swapBack()
        loadedFileName = ""
    }

    override fun onUnload() {
        onDisable()
    }

    override fun onTick() {
        if (fullNullCheck()) return
        
        val screen = mc.screen
        if (building && screen != null && screen.javaClass.name.contains("SignEdit")) {
            mc.setScreen(null)
        }

        if (file.value.currentFileName() != loadedFileName) {
            loadProjection()
        }

        if (layerBuild.value > 0 && rawProjection.blocks.isNotEmpty() && building) {
            val currentLayerY = minWorldY + currentBuildLayer
            var layerComplete = true
            var layerTotal = 0
            val transform = currentTransform()
            val level = mc.level
            if (level != null) {
                for (projBlock in rawProjection.blocks) {
                    if (shouldSkipProjectionBlock(projBlock.state)) continue
                    val worldPos = transform.apply(projBlock.pos)
                    if (worldPos.y == currentLayerY) {
                        layerTotal++
                        val expectedState = transform.applyState(projBlock.state)
                        val actual = level.getBlockState(worldPos)
                        if (!isProjectionBlockBuilt(actual, expectedState)) {
                            layerComplete = false
                            break
                        }
                    }
                }
                if (layerComplete && layerTotal > 0 && currentBuildLayer < (maxWorldY - minWorldY)) {
                    currentBuildLayer++
                    sendClientMessage("FastBuild: Layer ${currentBuildLayer} complete! Advancing to Layer ${currentBuildLayer + 1}")
                    updateFilteredProjection()
                }
            }
        }

        if (building) {
            placer.tick()
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (projection.blocks.isEmpty()) return
        LitematicLoader.render(
            context,
            projection,
            currentTransform(),
            LitematicLoader.RenderOptions(
                texture = true,
                fill = fill.value,
                outline = outline.value,
                renderBuilt = false,
                missingColor = missingColor.value.toANColor(),
                wrongColor = wrongColor.value.toANColor(),
                correctColor = builtColor.value.toANColor()
            )
        )
    }

    @ANEventHandler
    fun onPacketSend(event: PacketEvent.Send) {
        val packet = event.packet
        if (packet is ServerboundUseItemOnPacket && !building && enabled) {
            building = true
            updateFilteredProjection()
            sendClientMessage("FastBuild placement started!")
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val packet = event.packet
        if (building && (packet is ClientboundOpenScreenPacket || packet is ClientboundOpenSignEditorPacket)) {
            event.cancel()
            if (packet is ClientboundOpenScreenPacket) {
                mc.connection?.send(ServerboundContainerClosePacket(packet.containerId))
            }
            return
        }
    }

    private fun loadProjection() {
        val selected = file.value.currentFileName()
        loadedFileName = selected
        if (selected.isBlank()) {
            rawProjection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
            projection = rawProjection
            sendClientMessage("No FastBuild projection file selected")
            return
        }

        rawProjection = filterProjection(LitematicLoader.load(ANConfigManager.fastBuildFile(selected)))
        projection = rawProjection

        val player = mc.player
        if (player != null) {
            origin = player.blockPosition()
            val transform = currentTransform()
            val transformedY = rawProjection.blocks.map { transform.apply(it.pos).y }
            minWorldY = transformedY.minOrNull() ?: 0
            maxWorldY = transformedY.maxOrNull() ?: 0
            currentBuildLayer = 0
            updateFilteredProjection()
        }
        sendClientMessage("Loaded template ${rawProjection.name}: ${rawProjection.blocks.size} blocks")
    }

    private fun currentTransform(): LitematicLoader.Transform {
        return LitematicLoader.Transform(
            origin = origin.offset(offsetX.value, offsetY.value, offsetZ.value),
            rotation = when (rotate.value % 4) {
                1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180
                3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90
                else -> net.minecraft.world.level.block.Rotation.NONE
            }
        )
    }

    private fun rebuildPlaceCache(transform: LitematicLoader.Transform = currentTransform()) {
        placeBlocks = projection.blocks
            .asSequence()
            .filterNot { it.state.isAir }
            .map { BlockPlacer.PlaceBlock(transform.apply(it.pos), transform.applyState(it.state)) }
            .sortedWith(compareBy<BlockPlacer.PlaceBlock> { it.pos.y }.thenBy { it.pos.x }.thenBy { it.pos.z })
            .toList()

        schematicBlockMap.clear()
        for (block in placeBlocks) {
            schematicBlockMap[block.pos] = block.state
        }
    }

    private fun filterProjection(projection: LitematicLoader.Projection): LitematicLoader.Projection {
        return projection.copy(blocks = projection.blocks.filterNot { shouldSkipProjectionBlock(it.state) })
    }

    private fun shouldSkipProjectionBlock(state: BlockState): Boolean {
        return state.isAir || state.block is net.minecraft.world.level.block.CropBlock || state.block is net.minecraft.world.level.block.BushBlock || state.block is LiquidBlock
    }

    private fun isProjectionBlockBuilt(actual: BlockState, expected: BlockState): Boolean {
        return shouldSkipProjectionBlock(expected) || placer.isCompatibleState(actual, expected)
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private fun updateFilteredProjection() {
        val transform = currentTransform()
        if (layerBuild.value > 0 && building) {
            val maxAllowedY = minWorldY + currentBuildLayer + layerBuild.value - 1
            val filtered = rawProjection.blocks.filter {
                transform.apply(it.pos).y <= maxAllowedY
            }
            projection = rawProjection.copy(blocks = filtered)
        } else {
            projection = rawProjection
        }
        rebuildPlaceCache(transform)
    }

    @ANEventHandler
    fun onRender2D(event: Render2DEvent) {
        if (rawProjection.blocks.isEmpty()) return
        val window = mc.window
        val gui = MinecraftGuiRenderContext(event.context, mc.font, window.guiScaledWidth, window.guiScaledHeight)
        renderInfoPanel(gui)
    }

    private fun renderInfoPanel(context: ANGuiRenderContext) {
        val name = fitText(context, "Project: ${rawProjection.name}", HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
        val author = fitText(context, "Total: ${rawProjection.blocks.size}", HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
        val blocks = "Built $hudBuiltCount / ${rawProjection.blocks.filterNot { it.state.isAir }.size}"
        val placing = "AutoPlace: ${if (building) "Running" else "Idle"}"
        
        val lines = ArrayList<String>()
        lines.add(name)
        lines.add(author)
        lines.add(blocks)
        lines.add(placing)
        
        if (layerBuild.value > 0) {
            val layerText = "Layer: ${currentBuildLayer + 1} / ${maxWorldY - minWorldY + 1} (Limit: ${layerBuild.value})"
            lines.add(fitText(context, layerText, HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE))
        }

        val player = mc.player
        val level = mc.level
        var layerTotal = 0
        var layerBuilt = 0
        if (player != null && level != null && rawProjection.blocks.isNotEmpty()) {
            val currentLayerY = if (layerBuild.value > 0) minWorldY + currentBuildLayer else player.blockPosition().y
            val transform = currentTransform()
            for (projBlock in rawProjection.blocks) {
                if (shouldSkipProjectionBlock(projBlock.state)) continue
                val worldPos = transform.apply(projBlock.pos)
                if (worldPos.y == currentLayerY) {
                    layerTotal++
                    val expectedState = transform.applyState(projBlock.state)
                    val actual = level.getBlockState(worldPos)
                    if (isProjectionBlockBuilt(actual, expectedState)) {
                        layerBuilt++
                    }
                }
            }
        }
        
        if (layerTotal > 0) {
            val layerBlocksText = "Layer Blocks: $layerBuilt / $layerTotal (Left: ${layerTotal - layerBuilt})"
            lines.add(fitText(context, layerBlocksText, HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE))
        }
        
        val width = lines
            .maxOf { context.textWidth(it, HUD_TEXT_SCALE).toFloat() }
            .coerceAtMost(HUD_MAX_TEXT_WIDTH) + HUD_INNER_PADDING * 2f
        val height = HUD_INNER_PADDING * 3f + HUD_LINE_HEIGHT * lines.size - 10f
        val x = HUD_SCREEN_PADDING
        val y = context.height - height - HUD_SCREEN_PADDING

        context.borderedRoundedRect(x, y, width, height, 5f, 1f, INFO_PANEL_FILL, INFO_BORDER_FILL)
        
        for (i in lines.indices) {
            val color = when (i) {
                0 -> HudColors.text1
                1 -> HudColors.text2
                else -> HudColors.text3
            }
            context.text(lines[i], x + HUD_INNER_PADDING, y + 4f + HUD_LINE_HEIGHT * i + (if (i >= 2) 2f else 0f), color.rgb, HUD_TEXT_SCALE)
        }
    }

    private fun fitText(context: ANGuiRenderContext, text: String, maxWidth: Float, scale: Float): String {
        if (context.textWidth(text, scale) <= maxWidth) return text
        var end = text.length
        while (end > 0) {
            val candidate = text.take(end) + "..."
            if (context.textWidth(candidate, scale) <= maxWidth) return candidate
            end--
        }
        return "..."
    }

    private companion object {
        const val HUD_SCREEN_PADDING = 5f
        const val HUD_INNER_PADDING = 6f
        const val HUD_LINE_HEIGHT = 10f
        const val HUD_TEXT_SCALE = 0.8f
        const val HUD_MAX_TEXT_WIDTH = 160f
        val INFO_PANEL_FILL = Color(18, 20, 26, 185)
        val INFO_BORDER_FILL = Color(18, 250, 26, 185)
    }
}
