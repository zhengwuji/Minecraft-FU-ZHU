package anpilot.client.features.module.misc

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.autobuild.AuxiliaryBlockTask
import anpilot.client.features.ai.task.autobuild.MaterialRefillTask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.BaritoneHelper
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
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import anpilot.client.compat.Identifier
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.item.BlockItem
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.awt.Color
import java.util.Random
import kotlin.math.floor
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ShulkerBoxMenu
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType

class ANAutoBuild : ANBaseModule(
    name = "AutoBuild",
    description = "自动读取投影结构文件并在世界中按照材料表和层级精准自动建造",
    category = ANModuleCategory.MISC,
    chineseName = "自动建筑"
), ANWorldRenderModule {
    val page = addSetting(ANSetting("Page", Page.MAIN))

    val file = addSetting(ANSetting("File", FileSelectSetting(ANConfigManager::autoBuildFileNames)) { isPage(Page.MAIN) })
    val layerBuild = addSetting(ANSetting("LayerBuild", 1, 1, 5) { isPage(Page.MAIN) })

    val placeRange = addSetting(ANSetting("PlaceRange", 4.0f, 1.0f, 6.0f) { isPage(Page.MAIN) })
    val placeDelay = addSetting(ANSetting("PlaceDelay", 1, 0, 5) { isPage(Page.MAIN) })
    val blocksPerTick = addSetting(ANSetting("BlocksPerTick", 2, 1, 16) { isPage(Page.MAIN) })
    val inventorySwap = addSetting(ANSetting("InvSwap", true) { isPage(Page.MAIN) })
    val onlyBelowFeet = addSetting(ANSetting("OnlyFeet", true) { isPage(Page.MAIN) })
    val ignoreRedstoneOrientation = addSetting(ANSetting("NoRedstoneOrit", false) { isPage(Page.MAIN) })

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

    var rawProjection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
    private var projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
    var placeBlocks = emptyList<BlockPlacer.PlaceBlock>()
    var unbuiltLayerBlocks = emptyList<BlockPlacer.PlaceBlock>()
    private var buildTicks = 0
    private var loadedFileName = ""
    private var origin = BlockPos.ZERO
    var building = false
    var activeLayerTotal = 0
    var activeLayerBuilt = 0

    private val schematicBlockMap = HashMap<BlockPos, BlockState>()
    private val confirmedBuiltBlocks = HashSet<BlockPos>()
    val blacklistedBlocks = HashMap<BlockPos, Long>()

    var minWorldY = 0
    var maxWorldY = 0
    var currentBuildLayer = 0

    private var hudBuiltCount = 0
    private var hudScanCount = 0
    private var hudScanSectionIndex = 0
    private var hudScanBlockIndex = 0
    private var hudScanCacheKey = ""

    val chestListPos = ArrayList<ChestPair>()
    val materialChestMap = HashMap<Block, MutableList<ChestPair>>()
    val chestContents = HashMap<BlockPos, HashMap<Block, Int>>()
    private var chestMarkedPos: BlockPos? = null
    private var chestMarkedPos2: BlockPos? = null
    private var agent: ANAgent? = null

    private val placerContext = object : BlockPlacer.Context {
        override val placeRangeSqr: Float get() = placeRange.getPow2Value()
        override val blocksPerTick: Int get() = this@ANAutoBuild.blocksPerTick.value
        override val placeDelay: Int get() = this@ANAutoBuild.placeDelay.value
        override val inventorySwap: Boolean get() = this@ANAutoBuild.inventorySwap.value
        override val onlyBelowFeet: Boolean get() = this@ANAutoBuild.onlyBelowFeet.value
        override val ignoreRedstoneOrientation: Boolean get() = this@ANAutoBuild.ignoreRedstoneOrientation.value
        override val placeBlocks: List<BlockPlacer.PlaceBlock> get() = this@ANAutoBuild.placeBlocks
        override val schematicBlockMap: Map<BlockPos, BlockState> get() = this@ANAutoBuild.schematicBlockMap

        override fun shouldSkipBlock(state: BlockState) = shouldSkipProjectionBlock(state)
        override fun onBlockPlaced(pos: BlockPos) {
        }
    }
    val placer = BlockPlacer(placerContext)

    override fun onEnable() {
        if (fullNullCheck()) {
            disable()
            return
        }
        building = false
        placer.resetCooldown()
        currentBuildLayer = 0
        resetHudScan()
        loadProjection()
        agent = null
        BaritoneHelper.configure()
    }

    override fun onDisable() {
        agent?.stop()
        agent = null
        building = false
        projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
        rawProjection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
        placeBlocks = emptyList()
        placer.resetCooldown()
        confirmedBuiltBlocks.clear()
        Inventory.endSwap()
        Inventory.swapBack()
        loadedFileName = ""
        resetHudScan()

        chestListPos.clear()
        materialChestMap.clear()
        chestContents.clear()
        chestMarkedPos = null
        chestMarkedPos2 = null
        BaritoneHelper.cancel()
        BaritoneHelper.restore()
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

        val currentAgent = agent
        if (building && currentAgent != null) {
            currentAgent.tick()
        }

        val player = mc.player
        val level = mc.level
        if (player != null && level != null && rawProjection.blocks.isNotEmpty()) {
            val currentLayerY = minWorldY + currentBuildLayer
            var layerComplete = true
            var layerTotal = 0
            var built = 0
            val transform = currentTransform()
            for (projBlock in rawProjection.blocks) {
                if (shouldSkipProjectionBlock(projBlock.state)) continue
                val worldPos = transform.apply(projBlock.pos)
                if (worldPos.y == currentLayerY) {
                    layerTotal++
                    val expectedState = transform.applyState(projBlock.state)
                    val actual = level.getBlockState(worldPos)
                    if (isProjectionBlockBuilt(actual, expectedState)) {
                        built++
                    } else {
                        layerComplete = false
                    }
                }
            }
            activeLayerTotal = layerTotal
            activeLayerBuilt = built

            if (layerBuild.value > 0 && building && layerComplete && layerTotal > 0 && currentBuildLayer < (maxWorldY - minWorldY)) {
                currentBuildLayer++
                sendClientMessage("AutoBuild: Layer ${currentBuildLayer} complete! Advancing to Layer ${currentBuildLayer + 1}")
                updateFilteredProjection()
            }
        }

        scanBuiltBlocks()

        if (!building) {
            
            val hit = mc.hitResult
            if (hit != null && hit.type == HitResult.Type.BLOCK) {
                val blockHit = hit as BlockHitResult
                val pos = blockHit.blockPos
                val state = mc.level?.getBlockState(pos)
                if (state != null && state.block.toString().contains("chest")) {
                    if (state.hasProperty(ChestBlock.TYPE)) {
                        val chestType = state.getValue(ChestBlock.TYPE)
                        if (chestType != ChestType.SINGLE) {
                            val facing = state.getValue(ChestBlock.FACING)
                            val offsetDir = if (chestType == ChestType.RIGHT) {
                                facing.counterClockWise
                            } else {
                                facing.clockWise
                            }
                            chestMarkedPos = pos
                            chestMarkedPos2 = pos.relative(offsetDir)
                        } else {
                            chestMarkedPos = pos
                            chestMarkedPos2 = null
                        }
                    } else {
                        chestMarkedPos = pos
                        chestMarkedPos2 = null
                    }
                }
            }

            val handler = mc.player?.containerMenu
            if (handler is ChestMenu) {
                val uniqueBlocks = HashSet<Block>()
                val chestSize = handler.slots.size - 36
                for (slot in 0 until chestSize) {
                    val stack = handler.slots[slot].item
                    if (!stack.isEmpty && stack.item is BlockItem) {
                        uniqueBlocks.add((stack.item as BlockItem).block)
                    }
                }
                
                
                val neededUniqueBlocks = uniqueBlocks.filter { isBlockNeeded(it) }
                
                if (neededUniqueBlocks.isNotEmpty() && chestMarkedPos != null) {
                    val alreadyRegistered = chestListPos.any {
                        it.pos1 == chestMarkedPos || (chestMarkedPos2 != null && it.pos1 == chestMarkedPos2) || (it.pos2 != null && it.pos2 == chestMarkedPos)
                    }
                    if (!alreadyRegistered) {
                        val randomColor = Color(
                            Random().nextInt(200) + 55, 
                            Random().nextInt(200) + 55,
                            Random().nextInt(200) + 55
                        )
                        val pair = ChestPair(chestMarkedPos!!, chestMarkedPos2, randomColor)
                        chestListPos.add(pair)
                        
                        val contents = HashMap<Block, Int>()
                        for (slot in 0 until chestSize) {
                            val stack = handler.slots[slot].item
                            if (!stack.isEmpty && stack.item is BlockItem) {
                                val block = (stack.item as BlockItem).block
                                val canonical = getCanonicalBlock(block)
                                contents[canonical] = (contents[canonical] ?: 0) + stack.count
                            }
                        }
                        chestContents[pair.pos1] = contents
                        
                        val names = ArrayList<String>()
                        for (block in neededUniqueBlocks) {
                            addMaterialChest(block, pair)
                            names.add(BuiltInRegistries.BLOCK.getKey(block).path)
                        }
                        
                    } else {
                        val existingPair = chestListPos.first {
                            it.pos1 == chestMarkedPos || (chestMarkedPos2 != null && it.pos1 == chestMarkedPos2) || (it.pos2 != null && it.pos2 == chestMarkedPos)
                        }
                        
                        val contents = HashMap<Block, Int>()
                        for (slot in 0 until chestSize) {
                            val stack = handler.slots[slot].item
                            if (!stack.isEmpty && stack.item is BlockItem) {
                                val block = (stack.item as BlockItem).block
                                val canonical = getCanonicalBlock(block)
                                contents[canonical] = (contents[canonical] ?: 0) + stack.count
                            }
                        }
                        chestContents[existingPair.pos1] = contents
                        
                        var updated = false
                        val addedNames = ArrayList<String>()
                        val removedNames = ArrayList<String>()
                        
                        
                        for (block in neededUniqueBlocks) {
                            val chests = materialChestMap[block]
                            if (chests == null || existingPair !in chests) {
                                addMaterialChest(block, existingPair)
                                addedNames.add(BuiltInRegistries.BLOCK.getKey(block).path)
                                updated = true
                            }
                        }
                        
                        val blocksToRemove = ArrayList<Block>()
                        for ((block, chests) in materialChestMap) {
                            if (existingPair in chests && block !in neededUniqueBlocks) {
                                blocksToRemove.add(block)
                            }
                        }
                        for (block in blocksToRemove) {
                            materialChestMap[block]?.remove(existingPair)
                            if (materialChestMap[block]?.isEmpty() == true) {
                                materialChestMap.remove(block)
                            }
                            removedNames.add(BuiltInRegistries.BLOCK.getKey(block).path)
                            updated = true
                        }
                        
                        val isStillBound = materialChestMap.values.any { list -> existingPair in list }
                        if (!isStillBound) {
                            chestListPos.remove(existingPair)
                            chestContents.remove(existingPair.pos1)
                            sendClientMessage("箱子 $chestMarkedPos 内已无投影所需物料，已解除绑定。")
                        } else if (updated) {
                            var msg = "已更新位于 $chestMarkedPos 的物料源绑定列表"
                            if (addedNames.isNotEmpty()) msg += "（新增：${addedNames.joinToString(", ")}）"
                            if (removedNames.isNotEmpty()) msg += "（移除了不再含有或不需要的：${removedNames.joinToString(", ")}）"
                            sendClientMessage(msg)
                        }
                    }
                    mc.player?.closeContainer()
                } else if (neededUniqueBlocks.isEmpty() && chestMarkedPos != null) {
                    val existingPair = chestListPos.firstOrNull {
                        it.pos1 == chestMarkedPos || (chestMarkedPos2 != null && it.pos1 == chestMarkedPos2) || (it.pos2 != null && it.pos2 == chestMarkedPos)
                    }
                    if (existingPair != null) {
                        chestListPos.remove(existingPair)
                        chestContents.remove(existingPair.pos1)
                        for ((block, chests) in materialChestMap) {
                            chests.remove(existingPair)
                        }
                        materialChestMap.entries.removeIf { it.value.isEmpty() }
                        sendClientMessage("箱子 $chestMarkedPos 内已无投影所需物料，已解除绑定。")
                    } else {
                        sendClientMessage("箱子 $chestMarkedPos 不包含投影所需物料，未进行绑定。")
                    }
                    mc.player?.closeContainer()
                }
            }
        } else {
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

        for (pair in chestListPos) {
            val color = pair.color
            val fillCol = ANColor(color.red, color.green, color.blue, 50)
            val lineCol = ANColor(color.red, color.green, color.blue, 255)
            ANRender3DEngine.box(context, AABB(pair.pos1), lineCol, fillCol)
            if (pair.pos2 != null) {
               ANRender3DEngine.box(context, AABB(pair.pos2), lineCol, fillCol)
            }
        }

        val currentTask = agent?.scheduler?.current()
        if (currentTask is AuxiliaryBlockTask) {
            val tPos = currentTask.targetPos
            val aPos = currentTask.auxPos
            
            val fillColTarget = ANColor(255, 0, 0, 50)
            val lineColTarget = ANColor(255, 0, 0, 255)
            ANRender3DEngine.box(context, AABB(tPos), lineColTarget, fillColTarget)
            
            
            if (aPos != null) {
                val fillColAux = ANColor(0, 0, 255, 50)
                val lineColAux = ANColor(0, 0, 255, 255)
                ANRender3DEngine.box(context, AABB(aPos), lineColAux, fillColAux)
            }
        }
    }

    @ANEventHandler
    fun onPacketSend(event: PacketEvent.Send) {
        val packet = event.packet
        if (packet is ServerboundUseItemOnPacket && !building && enabled) {
            val player = mc.player ?: return
            val hand = packet.hand
            val stack = player.getItemInHand(hand)
            if (stack.item is BlockItem) {
                val hitPos = packet.hitResult.blockPos
                val clickedState = mc.level?.getBlockState(hitPos)
                if (clickedState != null) {
                    val blockName = clickedState.block.toString().lowercase()
                    if (blockName.contains("chest") || blockName.contains("shulker") || blockName.contains("barrel")) {
                        return
                    }
                }
                startAutoPlace()
            }
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val packet = event.packet
        if (building && (packet is ClientboundOpenScreenPacket || packet is ClientboundOpenSignEditorPacket)) {
            if (packet is ClientboundOpenScreenPacket) {
                if (agent?.scheduler?.current() is MaterialRefillTask) {
                    return
                }
                event.cancel()
                mc.connection?.send(ServerboundContainerClosePacket(packet.containerId))
            } else {
                event.cancel()
            }
            return
        }

        if (!building) return
        when (packet) {
            is ClientboundBlockUpdatePacket -> {
                updateConfirmedBuilt(packet.pos, packet.blockState)
            }
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { pos, state ->
                updateConfirmedBuilt(pos, state)
            }
        }
    }

    private fun loadProjection() {
        val selected = file.value.currentFileName()
        loadedFileName = selected
        if (selected.isBlank()) {
            rawProjection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
            projection = rawProjection
            confirmedBuiltBlocks.clear()
            sendClientMessage("No AutoBuild projection file selected")
            return
        }

        rawProjection = filterProjection(LitematicLoader.load(ANConfigManager.autoBuildFile(selected)))
        projection = rawProjection
        confirmedBuiltBlocks.clear()
        blacklistedBlocks.clear()

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

    fun currentTransform(): LitematicLoader.Transform {
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

    fun shouldSkipProjectionBlock(state: BlockState): Boolean {
        return state.isAir || state.block is net.minecraft.world.level.block.CropBlock || state.block is net.minecraft.world.level.block.BushBlock || state.block is LiquidBlock
    }

    private fun isProjectionBlockBuilt(actual: BlockState, expected: BlockState): Boolean {
        return shouldSkipProjectionBlock(expected) || placer.isCompatibleState(actual, expected)
    }

    fun isBuildTargetBuilt(block: BlockPlacer.PlaceBlock): Boolean = isBuildTargetBuilt(block.pos, block.state)

    fun findNearestUnbuiltBlock(from: BlockPos): BlockPlacer.PlaceBlock? {
        val now = System.currentTimeMillis()
        val player = mc.player
        val playerFeetY = if (player != null) floor(player.y - 0.001).toInt() + 1 else Int.MAX_VALUE

        val unbuilt = placeBlocks.asSequence()
            .filterNot { isBuildTargetBuilt(it) }
            .filter { pos -> !blacklistedBlocks.containsKey(pos.pos) || now >= (blacklistedBlocks[pos.pos] ?: 0L) }
            .filter { pos -> !onlyBelowFeet.value || pos.pos.y <= playerFeetY }
            .toList()
        if (unbuilt.isEmpty()) return null

        val currentLayerY = minWorldY + currentBuildLayer
        val currentLayerTarget = unbuilt
            .asSequence()
            .filter { it.pos.y == currentLayerY }
            .minWithOrNull(compareBy<BlockPlacer.PlaceBlock> { it.pos.distSqr(from) }
                .thenBy { it.pos.x }
                .thenBy { it.pos.z })
        if (currentLayerTarget != null) return currentLayerTarget

        return unbuilt.minWithOrNull(compareBy<BlockPlacer.PlaceBlock> { it.pos.distSqr(from) }
            .thenBy { it.pos.y }
            .thenBy { it.pos.x }
            .thenBy { it.pos.z })
    }

    fun getCanonicalBlock(block: Block): Block {
        val name = BuiltInRegistries.BLOCK.getKey(block).path
        if (name.endsWith("_wall_sign")) {
            val baseName = name.substringBefore("_wall_sign") + "_sign"
            val baseBlock = BuiltInRegistries.BLOCK.get(Identifier("minecraft", baseName))
            if (baseBlock != Blocks.AIR) return baseBlock
        }
        if (name.endsWith("_wall_hanging_sign")) {
            val baseName = name.substringBefore("_wall_hanging_sign") + "_hanging_sign"
            val baseBlock = BuiltInRegistries.BLOCK.get(Identifier("minecraft", baseName))
            if (baseBlock != Blocks.AIR) return baseBlock
        }
        if (name.endsWith("_wall_banner")) {
            val baseName = name.substringBefore("_wall_banner") + "_banner"
            val baseBlock = BuiltInRegistries.BLOCK.get(Identifier("minecraft", baseName))
            if (baseBlock != Blocks.AIR) return baseBlock
        }
        if (name == "wall_torch") {
            return Blocks.TORCH
        }
        if (name == "redstone_wall_torch") {
            return Blocks.REDSTONE_TORCH
        }
        if (name == "soul_wall_torch") {
            return Blocks.SOUL_TORCH
        }
        if (name.endsWith("_wall_skull") || name.endsWith("_wall_head")) {
            val baseName = name.replace("_wall_skull", "_skull").replace("_wall_head", "_head")
            val baseBlock = BuiltInRegistries.BLOCK.get(Identifier("minecraft", baseName))
            if (baseBlock != Blocks.AIR) return baseBlock
        }
        return block
    }

    fun materialChestPositions(block: Block): List<BlockPos> {
        val canonical = getCanonicalBlock(block)
        return materialChestMap[canonical]
            ?.map { pair -> pair.pos1 }
            ?.distinct()
            ?: emptyList()
    }

    fun getChestItemCount(block: Block): Int {
        val canonical = getCanonicalBlock(block)
        var count = 0
        for (pair in chestListPos) {
            val contents = chestContents[pair.pos1]
            if (contents != null) {
                count += contents[canonical] ?: 0
            }
        }
        return count
    }

    private fun addMaterialChest(block: Block, pair: ChestPair) {
        val canonical = getCanonicalBlock(block)
        val chests = materialChestMap.getOrPut(canonical) { ArrayList() }
        if (pair !in chests) {
            chests.add(pair)
        }
    }

    fun isBlockNeeded(block: Block): Boolean {
        if (rawProjection.blocks.isEmpty()) return false
        val canonicalTarget = getCanonicalBlock(block)
        val transform = currentTransform()
        return rawProjection.blocks.any { projBlock ->
            if (getCanonicalBlock(projBlock.state.block) != canonicalTarget) return@any false
            val worldPos = transform.apply(projBlock.pos)
            !isBuildTargetBuilt(worldPos, transform.applyState(projBlock.state))
        }
    }

    fun isSchematicFullyBuilt(): Boolean {
        if (rawProjection.blocks.isEmpty()) return false
        val transform = currentTransform()
        return rawProjection.blocks
            .asSequence()
            .filterNot { shouldSkipProjectionBlock(it.state) }
            .all { projBlock ->
                val worldPos = transform.apply(projBlock.pos)
                isBuildTargetBuilt(worldPos, transform.applyState(projBlock.state))
            }
    }

    fun isBlockNeededOnCurrentLayer(block: Block): Boolean {
        if (placeBlocks.isEmpty()) return false
        val canonicalTarget = getCanonicalBlock(block)
        return placeBlocks.any { placeBlock ->
            if (getCanonicalBlock(placeBlock.state.block) != canonicalTarget) return@any false
            !isBuildTargetBuilt(placeBlock)
        }
    }

    fun updateChestBindingsFromOpenContainer(handler: AbstractContainerMenu, pos: BlockPos) {
        if (handler !is ChestMenu && handler !is ShulkerBoxMenu) return
        
        val uniqueBlocks = HashSet<Block>()
        val contents = HashMap<Block, Int>()
        val storageSize = (handler.slots.size - 36).coerceAtLeast(0)
        for (slot in 0 until storageSize) {
            val stack = handler.slots[slot].item
            if (!stack.isEmpty && stack.item is BlockItem) {
                val block = (stack.item as BlockItem).block
                val canonical = getCanonicalBlock(block)
                uniqueBlocks.add(block)
                contents[canonical] = (contents[canonical] ?: 0) + stack.count
            }
        }
        
        val neededUniqueBlocks = uniqueBlocks.filter { isBlockNeeded(it) }.map { getCanonicalBlock(it) }.toSet()
        val existingPair = chestListPos.firstOrNull {
            it.pos1 == pos || (it.pos2 != null && it.pos2 == pos)
        } ?: return
        
        chestContents[existingPair.pos1] = contents
        
        var updated = false
        val removedNames = ArrayList<String>()
        val addedNames = ArrayList<String>()
        
        for (block in neededUniqueBlocks) {
            val chests = materialChestMap[block]
            if (chests == null || existingPair !in chests) {
                addMaterialChest(block, existingPair)
                addedNames.add(BuiltInRegistries.BLOCK.getKey(block).path)
                updated = true
            }
        }
        
        val blocksToRemove = ArrayList<Block>()
        for ((block, chests) in materialChestMap) {
            if (existingPair in chests && block !in neededUniqueBlocks) {
                blocksToRemove.add(block)
            }
        }
        for (block in blocksToRemove) {
            materialChestMap[block]?.remove(existingPair)
            if (materialChestMap[block]?.isEmpty() == true) {
                materialChestMap.remove(block)
            }
            removedNames.add(BuiltInRegistries.BLOCK.getKey(block).path)
            updated = true
        }
        
        val isStillBound = materialChestMap.values.any { list -> existingPair in list }
        if (!isStillBound) {
            chestListPos.remove(existingPair)
            chestContents.remove(existingPair.pos1)
            sendClientMessage("箱子 ${existingPair.pos1} 内无投影所需物料，已解除绑定。")
        } else if (updated) {
            var msg = "已更新位于 ${existingPair.pos1} 的物料源绑定列表"
            if (addedNames.isNotEmpty()) msg += "（新增：${addedNames.joinToString(", ")}）"
            if (removedNames.isNotEmpty()) msg += "（移除：${removedNames.joinToString(", ")}）"
            sendClientMessage(msg)
        }
    }

    fun isBuildTargetBuilt(pos: BlockPos, expected: BlockState): Boolean {
        if (confirmedBuiltBlocks.contains(pos)) return true
        val level = mc.level ?: return false
        val actual = level.getBlockState(pos)
        val built = placer.isBlockBuilt(actual, expected)
        if (built) {
            confirmedBuiltBlocks.add(pos)
        }
        return built
    }

    private fun updateConfirmedBuilt(pos: BlockPos, actual: BlockState) {
        val expected = schematicBlockMap[pos] ?: return
        if (isProjectionBlockBuilt(actual, expected)) {
            confirmedBuiltBlocks.add(pos)
        } else {
            confirmedBuiltBlocks.remove(pos)
        }
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    data class ChestPair(val pos1: BlockPos, val pos2: BlockPos?, val color: Color)

    fun updateFilteredProjection() {
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
        updateUnbuiltLayerBlocks()
    }

    fun updateUnbuiltLayerBlocks() {
        val now = System.currentTimeMillis()
        val currentLayerY = minWorldY + currentBuildLayer
        val maxLayerY = if (layerBuild.value > 0) currentLayerY + layerBuild.value - 1 else currentLayerY
        val pPos = mc.player?.blockPosition()

        val candidates = placeBlocks
            .asSequence()
            .filter { it.pos.y in currentLayerY..maxLayerY }
            .filterNot { isBuildTargetBuilt(it) }
            .filter { pos -> !blacklistedBlocks.containsKey(pos.pos) || now >= (blacklistedBlocks[pos.pos] ?: 0L) }
            .toList()

        if (pPos == null || candidates.isEmpty()) {
            unbuiltLayerBlocks = candidates
            return
        }

        
        val groupedByXZ = candidates
            .groupBy { Pair(it.pos.x, it.pos.z) }
            .mapValues { entry -> entry.value.sortedBy { it.pos.y } }

        
        val sortedColumns = groupedByXZ.keys.sortedBy { (x, z) ->
            val dx = x - pPos.x
            val dz = z - pPos.z
            dx * dx + dz * dz
        }

        
        val sortedList = ArrayList<BlockPlacer.PlaceBlock>()
        for (columnKey in sortedColumns) {
            val columnBlocks = groupedByXZ[columnKey] ?: continue
            sortedList.addAll(columnBlocks)
        }

        unbuiltLayerBlocks = sortedList
    }

    private fun scanBuiltBlocks() {
        hudBuiltCount = confirmedBuiltBlocks.size
    }

    private fun resetHudScan(cacheKey: String = "") {
        hudBuiltCount = 0
        hudScanCount = 0
        hudScanSectionIndex = 0
        hudScanBlockIndex = 0
        hudScanCacheKey = cacheKey
    }

    fun getInventoryItemCount(block: Block): Int {
        val player = mc.player ?: return 0
        val canonical = getCanonicalBlock(block)
        var count = 0
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty) {
                val item = stack.item
                if (item is BlockItem) {
                    val itemBlock = item.block
                    if (itemBlock == block || getCanonicalBlock(itemBlock) == canonical) {
                        count += stack.count
                    }
                }
            }
        }
        return count
    }

    private fun startAutoPlace() {
        if (building) return
        if (projection.blocks.isEmpty()) {
            sendClientMessage("Projection is empty, AutoBuild placing not started.")
            disable()
            return
        }
        building = true
        placer.resetCooldown()
        updateFilteredProjection()
        
        val nextAgent = ANAgent(this)
        agent = nextAgent
        nextAgent.start()

        AgentUtils.sendMessage("任务开始!")
    }

    @ANEventHandler
    fun onRender2D(event: Render2DEvent) {
        if (rawProjection.blocks.isEmpty()) return
        val window = mc.window
        val gui = MinecraftGuiRenderContext(event.context, mc.font, window.guiScaledWidth, window.guiScaledHeight)

        val blockCounts = projection.blocks.groupingBy { getCanonicalBlock(it.state.block) }.eachCount()
        val neededBlocks = blockCounts.keys.toList()
        val secondHeight = if (neededBlocks.isEmpty()) 0f else HUD_INNER_PADDING * 2f + neededBlocks.size * HUD_LINE_HEIGHT

        val firstHeight = renderInfoPanel(gui, secondHeight)

        val x = HUD_SCREEN_PADDING
        val secondY = gui.height - secondHeight - HUD_SCREEN_PADDING
        if (secondHeight > 0f) {
            renderMaterialPanelAt(gui, x, secondY, neededBlocks, blockCounts)
        }
    }

    private fun renderInfoPanel(context: ANGuiRenderContext, secondHeight: Float): Float {
        val name = fitText(context, "Project: ${rawProjection.name}", HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
        val author = fitText(context, "Total: ${rawProjection.blocks.size}", HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
        val blocks = "Built: $hudBuiltCount / ${rawProjection.blocks.filterNot { it.state.isAir }.size}"
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
                    if (isBuildTargetBuilt(worldPos, transform.applyState(projBlock.state))) {
                        layerBuilt++
                    }
                }
            }
        }

        if (layerTotal > 0) {
            val layerBlocksText = "Layer Blocks: $layerBuilt / $layerTotal"
            lines.add(fitText(context, layerBlocksText, HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE))
        }

        val width = lines
            .maxOf { context.textWidth(it, HUD_TEXT_SCALE).toFloat() }
            .coerceAtMost(HUD_MAX_TEXT_WIDTH) + HUD_INNER_PADDING * 2f
        val height = HUD_INNER_PADDING * 3f + HUD_LINE_HEIGHT * lines.size - 10f

        val x = HUD_SCREEN_PADDING
        val y = if (secondHeight > 0f) {
            context.height - secondHeight - HUD_SCREEN_PADDING - height - 5f
        } else {
            context.height - height - HUD_SCREEN_PADDING
        }

        context.borderedRoundedRect(x, y, width, height, 5f, 1f, INFO_PANEL_FILL, INFO_BORDER_FILL)

        for (i in lines.indices) {
            val color = when (i) {
                0 -> HudColors.text1
                1 -> HudColors.text2
                else -> HudColors.text3
            }
            context.text(lines[i], x + HUD_INNER_PADDING, y + 4f + HUD_LINE_HEIGHT * i + (if (i >= 2) 2f else 0f), color.rgb, HUD_TEXT_SCALE)
        }

        return height
    }

    private fun renderMaterialPanelAt(
        context: ANGuiRenderContext,
        x: Float,
        y: Float,
        neededBlocks: List<Block>,
        blockCounts: Map<Block, Int>
    ) {
        val transform = currentTransform()
        val blockBuiltCounts = HashMap<Block, Int>()
        for (projBlock in projection.blocks) {
            if (shouldSkipProjectionBlock(projBlock.state)) continue
            val canonical = getCanonicalBlock(projBlock.state.block)
            val worldPos = transform.apply(projBlock.pos)
            if (isBuildTargetBuilt(worldPos, transform.applyState(projBlock.state))) {
                blockBuiltCounts[canonical] = (blockBuiltCounts[canonical] ?: 0) + 1
            }
        }

        val lines = neededBlocks.map { block ->
            val name = block.name.string
            val total = blockCounts[block] ?: 0
            val built = blockBuiltCounts[block] ?: 0
            
            val isBound = materialChestMap.containsKey(block)
            val inventoryCount = getInventoryItemCount(block)
            val chestCount = getChestItemCount(block)
            val totalAvailable = inventoryCount + chestCount
            val neededLeft = (total - built).coerceAtLeast(0)
            
            val text = if (building) {
                "$name: $built/$total($totalAvailable)"
            } else {
                "$name: $totalAvailable/$neededLeft"
            }
            
            val isReady = totalAvailable >= neededLeft
            val color = if (building) {
                if (built >= total && total > 0) 0xFF64FF64.toInt() else 0xFFFFE664.toInt()
            } else {
                if (isReady) 0xFF64FF64.toInt() else 0xFFFFE664.toInt()
            }
            text to color
        }

        val width = lines.maxOf { context.textWidth(it.first, HUD_TEXT_SCALE).toFloat() }
            .coerceAtMost(HUD_MAX_TEXT_WIDTH) + HUD_INNER_PADDING * 2f
        val height = HUD_INNER_PADDING * 2f + lines.size * HUD_LINE_HEIGHT

        context.borderedRoundedRect(x, y, width, height, 5f, 1f, INFO_PANEL_FILL, INFO_BORDER_FILL)

        var currentY = y + HUD_INNER_PADDING
        for ((text, color) in lines) {
            val fitted = fitText(context, text, HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
            context.text(fitted, x + HUD_INNER_PADDING, currentY, color, HUD_TEXT_SCALE)
            currentY += HUD_LINE_HEIGHT
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
        const val CACHE_BUILD_BLOCKS_PER_TICK = 20_000
        const val CACHE_SNAPSHOT_INTERVAL_TICKS = 2
        const val BUILT_SCAN_BLOCKS_PER_TICK = 20_000
        const val HUD_SCREEN_PADDING = 5f
        const val HUD_INNER_PADDING = 6f
        const val HUD_LINE_HEIGHT = 10f
        const val HUD_TEXT_SCALE = 0.8f
        const val HUD_MAX_TEXT_WIDTH = 160f
        val INFO_PANEL_FILL = Color(18, 20, 26, 185)
        val INFO_BORDER_FILL = Color(18, 250, 26, 185)

    }
}
