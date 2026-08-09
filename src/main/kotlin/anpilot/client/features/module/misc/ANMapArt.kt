package anpilot.client.features.module.misc

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.ai.utils.LitematicLoader
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.event.impl.Render2DEvent
import anpilot.client.features.manager.ANConfigManager
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.inventory.SilentSwapType
import anpilot.client.features.manager.rotation.Rotation
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.module.hud.HudColors
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.Bind
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.setting.impl.FileSelectSetting
import anpilot.client.minecraft.gui.MinecraftGuiRenderContext
import anpilot.client.renderer.ANColor
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import anpilot.client.features.ai.utils.BaritoneHelper
import anpilot.client.features.utility.ANTimer
import anpilot.client.renderer.render.ANRender3DEngine
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.HitResult

class ANMapArt : ANBaseModule(
    name = "AutoMapArt",
    description = "加载并渲染地图画/建筑物投影结构，辅助进行平铺地图画绘制",
    category = ANModuleCategory.MISC,
    chineseName = "自动地图画"
), ANWorldRenderModule {
    val page = addSetting(ANSetting("Page", Page.MAIN))

    val file = addSetting(ANSetting("File", FileSelectSetting(ANConfigManager::mapArtFileNames)) { isPage(Page.MAIN) })
    val offsetX = addSetting(ANSetting("OffsetX", 0, -64, 64) { isPage(Page.RENDER) })
    val offsetY = addSetting(ANSetting("OffsetY", 0, -32, 32) { isPage(Page.RENDER) })
    val offsetZ = addSetting(ANSetting("OffsetZ", 0, -64, 64) { isPage(Page.RENDER) })
    val rotate = addSetting(ANSetting("Rotate", 0, 0, 3) { isPage(Page.RENDER) })
    val placeRange = addSetting(ANSetting("PlaceRange", 4.0f, 1.0f, 6.0f) { isPage(Page.MAIN) })
    val placeDelay = addSetting(ANSetting("PlaceDelay", 1, 0, 5) { isPage(Page.MAIN) })
    val blocksPerTick = addSetting(ANSetting("BlocksPerTick", 1, 1, 16) { isPage(Page.MAIN) })
    val inventorySwap = addSetting(ANSetting("InventorySwap", true) { isPage(Page.MAIN) })
    val refillStacks = addSetting(ANSetting("RefillStacks", 5, 1, 27) { isPage(Page.MAIN) })
    val placeRotate = addSetting(ANSetting("PlaceRotate", true) { isPage(Page.MAIN) })

    val fill = addSetting(ANSetting("Fill", false) { isPage(Page.RENDER) })
    val outline = addSetting(ANSetting("Outline", false) { isPage(Page.RENDER) })
    val missingColor = addSetting(ANSetting("MissingColor", ColorGroupSetting(Color(70, 170, 255, 70).rgb)) { isPage(Page.RENDER) })
    val wrongColor = addSetting(ANSetting("WrongColor", ColorGroupSetting(Color(255, 80, 80, 90).rgb)) { isPage(Page.RENDER) })
    val builtColor = addSetting(ANSetting("BuiltColor", ColorGroupSetting(Color(80, 255, 120, 45).rgb)) { isPage(Page.RENDER) })

    private var projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
    private var placeCacheTransform = LitematicLoader.Transform(BlockPos.ZERO)
    private var placeCacheProjectionName = ""
    private var placeCacheBlockCount = 0
    private var placeBlocks = emptyList<PlaceBlock>()
    private var placeCooldown = 0
    private var loadedFileName = ""
    private var origin = BlockPos.ZERO
    private var placingStarted = false
    private var hudBuiltCount = 0
    private var hudScanCount = 0
    private var hudScanSectionIndex = 0
    private var hudScanBlockIndex = 0
    private var hudScanCacheKey = ""
    private val chestListPos = ArrayList<ArrayList<ChestPair>>().apply {
        for (i in 0..15) {
            add(ArrayList())
        }
    }
    private val materialChestMap = HashMap<Block, Int>()
    private var chestMarkedPos: BlockPos? = null
    private var chestMarkedPos2: BlockPos? = null
    private var isMaterialRefill = false
    private var isWaitingForChest = false
    private val materialRefillPos = ArrayList<BlockPos>()
    private var goPoints = ArrayList<BlockPos>()
    private var goPointsIndex = 0
    private var repairMode = false
    private var repairBlocks = emptyList<PlaceBlock>()
    private var repairPass = 0
    private var completionStableTicks = 0
    private val chestTransferTimer = ANTimer()

    enum class Page {
        RENDER,
        MAIN,
    }

    private fun isPage(target: Page): Boolean = page.value == target

    override fun onEnable() {
        if (fullNullCheck()){
            disable()
            return
        }
        origin = mc.player?.blockPosition() ?: BlockPos.ZERO
        placingStarted = false
        placeCooldown = 0
        repairMode = false
        repairBlocks = emptyList()
        repairPass = 0
        completionStableTicks = 0
        loadProjection()
    }

    override fun onDisable() {
        placingStarted = false
        projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
        placeBlocks = emptyList()
        placeCooldown = 0
        Inventory.endSwap()
        Inventory.swapBack()
        loadedFileName = ""

        chestListPos.forEach { it.clear() }
        materialChestMap.clear()
        chestMarkedPos = null
        chestMarkedPos2 = null
        isMaterialRefill = false
        isWaitingForChest = false
        materialRefillPos.clear()
        goPoints.clear()
        goPointsIndex = 0
        repairMode = false
        repairBlocks = emptyList()
        repairPass = 0
        completionStableTicks = 0
        BaritoneHelper.cancel()
    }

    override fun onUnload() {
        onDisable()
    }

    override fun onTick() {
        if (fullNullCheck()) return
        if (file.value.currentFileName() != loadedFileName) {
            loadProjection()
        }
        ensurePlaceCache()

        if (!placingStarted) {
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
                var carpetPath: String? = null
                var materialBlock: Block? = null
                val chestSize = handler.slots.size - 36
                for (slot in 0 until chestSize) {
                    val stack = handler.slots[slot].item
                    if (!stack.isEmpty) {
                        val path = BuiltInRegistries.ITEM.getKey(stack.item).path
                        if (path.endsWith("_carpet")) {
                            carpetPath = path
                            if (materialBlock == null && stack.item is BlockItem) {
                                materialBlock = (stack.item as BlockItem).block
                            }
                        } else if (stack.item is BlockItem) {
                            materialBlock = (stack.item as BlockItem).block
                        }
                    }
                }
                if (carpetPath != null && chestMarkedPos != null) {
                    val idx = getCarpetColorIndex(carpetPath)
                    if (idx in 0..15) {
                        val pair = ChestPair(chestMarkedPos!!, chestMarkedPos2)
                        val alreadyRegistered = chestListPos[idx].any {
                            it.pos1 == chestMarkedPos || (chestMarkedPos2 != null && it.pos1 == chestMarkedPos2) || (it.pos2 != null && it.pos2 == chestMarkedPos)
                        }
                        if (!alreadyRegistered) {
                            chestListPos[idx].add(pair)
                            if (materialBlock != null) {
                                materialChestMap[materialBlock] = idx
                                sendClientMessage("已将位于 $chestMarkedPos 的箱子绑定为索引 $idx 的第 ${chestListPos[idx].size} 个物料源（包含：${BuiltInRegistries.BLOCK.getKey(materialBlock).path}）")
                            } else {
                                sendClientMessage("已将位于 $chestMarkedPos 的箱子绑定为索引 $idx 的第 ${chestListPos[idx].size} 个物料源（未在箱内找到方块）")
                            }
                        } else {
                            sendClientMessage("位于 $chestMarkedPos 的箱子已在绑定列表中，跳过")
                        }
                        mc.player?.closeContainer()
                    }
                }
            }
        } else {
            checkMaterialRefill()

            if (isMaterialRefill && materialRefillPos.isNotEmpty()) {
                val chestPos = materialRefillPos.first()
                if (!isWaitingForChest) {
                    val distance = mc.player?.eyePosition?.distanceTo(Vec3.atCenterOf(chestPos)) ?: 999.0
                    if (distance > 2.0) {
                        BaritoneHelper.pathNear(chestPos, 1)
                    } else {
                        BaritoneHelper.cancel()
                        isWaitingForChest = true
                    }
                }

                if (isWaitingForChest && mc.player?.containerMenu == mc.player?.inventoryMenu) {
                    val target = materialRefillPos.firstOrNull()
                    if (target != null) {
                        openChest(target)
                    }
                }

                val handler = mc.player?.containerMenu
                if (handler is ChestMenu) {
                    moveChestItems(handler)
                }
            } else {
                if (goPoints.isNotEmpty()) {
                    if (goPointsIndex < goPoints.size) {
                        val target = goPoints[goPointsIndex]
                        val dist = mc.player?.eyePosition?.distanceTo(Vec3.atCenterOf(target)) ?: 999.0
                        if (dist > 1.5) {
                            BaritoneHelper.pathTo(target)
                        } else {
                            goPointsIndex++
                        }
                    } else {
                        goPoints.clear()
                        BaritoneHelper.cancel()
                        finishCurrentPathPass()
                    }
                } else if (repairMode) {
                    verifyRepairPass()
                } else if (placeBlocks.isNotEmpty()) {
                    pathToFirstMissingBlock(placeBlocks)
                }

                tickAutoPlace()
            }
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

        for (i in 0..15) {
            val list = chestListPos[i]
            if (list.isEmpty()) continue
            val color = getCarpetColor(i)
            val fillCol = ANColor(color.red, color.green, color.blue, 50)
            val lineCol = ANColor(color.red, color.green, color.blue, 255)

            for (pair in list) {
                ANRender3DEngine.box(context, AABB(pair.pos1), lineCol, fillCol)
                if (pair.pos2 != null) {
                    ANRender3DEngine.box(context, AABB(pair.pos2), lineCol, fillCol)
                }
            }
        }
    }

    @ANEventHandler
    fun onPacketSend(event: PacketEvent.Send) {
        val packet = event.packet
        if (packet is ServerboundUseItemOnPacket && !placingStarted && enabled) {
            val player = mc.player ?: return
            val hand = packet.hand
            val stack = player.getItemInHand(hand)
            val path = BuiltInRegistries.ITEM.getKey(stack.item).path
            if (path.endsWith("_carpet")) {
                val hitPos = packet.hitResult.blockPos
                val clickedState = mc.level?.getBlockState(hitPos)
                if (clickedState != null && clickedState.block.toString().contains("chest")) {
                    return
                }
                startAutoPlace()
            }
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
    }

    @ANEventHandler
    fun onRender2D(event: Render2DEvent) {
        if (projection.blocks.isEmpty()) return
        val window = mc.window
        val gui = MinecraftGuiRenderContext(event.context, mc.font, window.guiScaledWidth, window.guiScaledHeight)

        val firstHeight = HUD_INNER_PADDING * 3f + HUD_LINE_HEIGHT * 4f - 10f

        val blockCounts = projection.blocks.groupingBy { it.state.block }.eachCount()
        val neededBlocks = blockCounts.keys.toList()
        val secondHeight = if (neededBlocks.isEmpty()) 0f else HUD_INNER_PADDING * 2f + neededBlocks.size * HUD_LINE_HEIGHT

        val x = HUD_SCREEN_PADDING
        val secondY = gui.height - secondHeight - HUD_SCREEN_PADDING
        val firstY = if (secondHeight > 0f) secondY - firstHeight - 5f else gui.height - firstHeight - HUD_SCREEN_PADDING

        renderInfoPanelAt(gui, x, firstY)
        if (secondHeight > 0f) {
            renderMaterialPanelAt(gui, x, secondY, neededBlocks, blockCounts)
        }
    }

    private fun loadProjection() {
        val selected = file.value.currentFileName()
        loadedFileName = selected
        if (selected.isBlank()) {
            projection = LitematicLoader.Projection("", emptyList(), LitematicLoader.Bounds.EMPTY)
            sendClientMessage("未选择投影文件")
            return
        }

        val rawProjection = LitematicLoader.load(ANConfigManager.mapArtFile(selected))
        if (rawProjection.blocks.isNotEmpty()) {
            val minY = rawProjection.blocks.minOf { it.pos.y }
            val filteredBlocks = rawProjection.blocks.filter { it.pos.y == minY }
            projection = LitematicLoader.Projection(
                name = rawProjection.name,
                blocks = filteredBlocks,
                bounds = rawProjection.bounds
            )
        } else {
            projection = rawProjection
        }

        sendClientMessage("已成功加载投影 ${projection.name}：共 ${projection.blocks.size} 个方块（已限制为第 1 层）")
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

    private fun resetHudScan(cacheKey: String = "") {
        hudBuiltCount = 0
        hudScanCount = 0
        hudScanSectionIndex = 0
        hudScanBlockIndex = 0
        hudScanCacheKey = cacheKey
    }

    private fun ensurePlaceCache() {
        val transform = currentTransform()
        if (
            placeCacheProjectionName == projection.name &&
            placeCacheBlockCount == projection.blocks.size &&
            placeCacheTransform == transform
        ) {
            return
        }
        rebuildPlaceCache(transform)
    }

    private fun rebuildPlaceCache(transform: LitematicLoader.Transform = currentTransform()) {
        placeCacheTransform = transform
        placeCacheProjectionName = projection.name
        placeCacheBlockCount = projection.blocks.size
        repairMode = false
        repairBlocks = emptyList()
        repairPass = 0
        completionStableTicks = 0
        val rawBlocks = projection.blocks
            .asSequence()
            .filterNot { it.state.isAir }
            .map { PlaceBlock(transform.apply(it.pos), transform.applyState(it.state)) }
            .toList()

        if (rawBlocks.isEmpty()) {
            placeBlocks = emptyList()
            return
        }

        val minZ = rawBlocks.minOf { it.pos.z }
        val bandWidth = 4

        placeBlocks = rawBlocks.sortedWith(
            compareBy<PlaceBlock> { it.pos.y }
                .thenComparator { b1, b2 ->
                    val band1 = (b1.pos.z - minZ) / bandWidth
                    val band2 = (b2.pos.z - minZ) / bandWidth
                    if (band1 != band2) {
                        band1.compareTo(band2)
                    } else {
                        val leftToRight = band1 % 2 == 0
                        if (leftToRight) {
                            b1.pos.x.compareTo(b2.pos.x)
                        } else {
                            b2.pos.x.compareTo(b1.pos.x)
                        }
                    }
                }
                .thenBy { it.pos.z }
        )
    }

    private fun tickAutoPlace() {
        val player = mc.player ?: return
        if (mc.level == null || player.isSpectator) return
        if (placeCooldown > 0) {
            placeCooldown--
            return
        }

        var placed = 0
        for (block in activePlaceBlocks()) {
            if (placed >= blocksPerTick.value) break
            if (!canPlaceProjectionBlock(block)) continue
            if (placeProjectionBlock(block)) {
                placed++
            }
        }

        if (placed > 0) {
            placeCooldown = placeDelay.value
            ANServiceRegistry.runtime.rotationManager.resetSilentRotation()
        }
    }

    private fun canPlaceProjectionBlock(block: PlaceBlock): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(block.pos)) > placeRange.getPow2Value()) return false
        if (!level.isInWorldBounds(block.pos)) return false

        val actual = level.getBlockState(block.pos)
        if (actual.block == block.state.block) return false

        if (!actual.isAir && actual.block != block.state.block) {
            mc.gameMode?.destroyBlock(block.pos)
            return false
        }

        if (!actual.isAir && !actual.canBeReplaced()) return false
        if (hasEntityBlocking(AABB(block.pos))) return false

        return supportHitResult(block.pos) != null && findBlockSlot(block.state.block.asItem()) != Inventory.INVALID_SLOT
    }

    private fun placeProjectionBlock(block: PlaceBlock): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        val hit = supportHitResult(block.pos) ?: return false
        val slot = findBlockSlot(block.state.block.asItem())
        if (slot == Inventory.INVALID_SLOT) return false

        val swapType = if (slot in 0 until Inventory.HOTBAR_SIZE) SilentSwapType.HOTBAR else SilentSwapType.INVENTORY
        val oldSelectedSlot = player.inventory.selected
        if (!Inventory.startSwap(slot, swapType)) return false
        val locallySwappedHotbar = swapType == SilentSwapType.HOTBAR && oldSelectedSlot != slot
        if (locallySwappedHotbar) {
            player.inventory.selected = slot
        }

        return try {
            if (placeRotate.value) {
                val rotations = RotationUtil.getRotationsTo(player.eyePosition, hit.location)
                ANServiceRegistry.runtime.rotationManager.setSilentRotation(Rotation(rotations[0], rotations[1]))
            }

            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            player.swing(InteractionHand.MAIN_HAND)
            true
        } finally {
            if (locallySwappedHotbar) {
                player.inventory.selected = oldSelectedSlot
            }
            Inventory.endSwap(swapType)
        }
    }

    private fun supportHitResult(pos: BlockPos): BlockHitResult? {
        val level = mc.level ?: return null
        val below = pos.below()
        if (level.getBlockState(below).canClick(level, below)) {
            return BlockHitResult(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), Direction.UP, below, false)
        }

        for (direction in Direction.entries) {
            val neighbor = pos.relative(direction.opposite)
            if (!level.getBlockState(neighbor).canClick(level, neighbor)) continue
            return BlockHitResult(Vec3.atCenterOf(neighbor), direction, neighbor, false)
        }
        return null
    }

    private fun findBlockSlot(item: Item): Int {
        if (item == Items.AIR) return Inventory.INVALID_SLOT
        val hotbar = Inventory.find(0 until Inventory.HOTBAR_SIZE) { stack ->
            stack.item == item && stack.item is BlockItem
        }
        if (hotbar.found) return hotbar.slot
        if (!inventorySwap.value) return Inventory.INVALID_SLOT

        val inventory = Inventory.find(Inventory.HOTBAR_SIZE until Inventory.MAIN_SIZE) { stack ->
            stack.item == item && stack.item is BlockItem
        }
        return if (inventory.found) inventory.slot else Inventory.INVALID_SLOT
    }

    private fun hasEntityBlocking(box: AABB): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        return level.getEntities(player, box) { entity ->
            !entity.isRemoved && entity.type != EntityType.EXPERIENCE_ORB
        }.isNotEmpty()
    }

    private fun renderInfoPanelAt(context: ANGuiRenderContext, x: Float, y: Float) {
        val name = fitText(context, "项目: ${projection.name}", HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
        val author = fitText(context, "总数: ${projection.blocks.size}", HUD_MAX_TEXT_WIDTH, HUD_TEXT_SCALE)
        val blocks = "已建: $hudBuiltCount / ${projection.blocks.size}"
        val placing = "自动放置: ${if (placingStarted) "运行中" else "空闲"}"
        val width = listOf(name, author, blocks, placing)
            .maxOf { context.textWidth(it, HUD_TEXT_SCALE).toFloat() }
            .coerceAtMost(HUD_MAX_TEXT_WIDTH) + HUD_INNER_PADDING * 2f
        val height = HUD_INNER_PADDING * 3f + HUD_LINE_HEIGHT * 4f - 10f

        context.borderedRoundedRect(x, y, width, height, 5f, 1f, INFO_PANEL_FILL, INFO_BORDER_FILL)
        context.text(name, x + HUD_INNER_PADDING, y + 4f, HudColors.text1.rgb, HUD_TEXT_SCALE)
        context.text(author, x + HUD_INNER_PADDING, y + 4f + HUD_LINE_HEIGHT, HudColors.text2.rgb, HUD_TEXT_SCALE)
        context.text(blocks, x + HUD_INNER_PADDING, y + 4f + HUD_LINE_HEIGHT * 2f + 2F, HudColors.text3.rgb, HUD_TEXT_SCALE)
        context.text(placing, x + HUD_INNER_PADDING, y + 4f + HUD_LINE_HEIGHT * 3f + 2F, HudColors.text3.rgb, HUD_TEXT_SCALE)
    }

    private fun renderMaterialPanelAt(
        context: ANGuiRenderContext,
        x: Float,
        y: Float,
        neededBlocks: List<Block>,
        blockCounts: Map<Block, Int>
    ) {
        val lines = neededBlocks.map { block ->
            val name = block.name.string
            val count = blockCounts[block] ?: 0
            val text = "$name: $count"
            val isBound = materialChestMap.containsKey(block)
            val inventoryCount = getInventoryItemCount(block)
            val isReady = isBound || inventoryCount >= count
            val color = if (isReady) 0xFF64FF64.toInt() else 0xFFFFE664.toInt()
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

    private fun startAutoPlace() {
        if (placingStarted) return
        if (projection.blocks.isEmpty()) {
            sendClientMessage("投影内容为空，未启动自动放置")
            return
        }
        ensurePlaceCache()
        goPoints = ArrayList(generateSnakeBoundaryPoints(placeBlocks, 4))
        goPointsIndex = 0
        placeCooldown = 0
        repairMode = false
        repairBlocks = emptyList()
        repairPass = 0
        completionStableTicks = 0
        placingStarted = true
        sendClientMessage("自动放置已成功启动")
    }

    private fun generateSnakeBoundaryPoints(blocks: List<PlaceBlock>, bandWidth: Int): List<BlockPos> {
        val points = ArrayList<BlockPos>()
        if (blocks.isEmpty()) return points

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        var minY = Int.MAX_VALUE

        for (block in blocks) {
            val pos = block.pos
            minX = minOf(minX, pos.x)
            maxX = maxOf(maxX, pos.x)
            minZ = minOf(minZ, pos.z)
            maxZ = maxOf(maxZ, pos.z)
            minY = minOf(minY, pos.y)
        }

        var leftToRight = true
        for (z in minZ..maxZ step bandWidth) {
            if (leftToRight) {
                points.add(BlockPos(minX, minY, z))
                points.add(BlockPos(maxX, minY, z))
            } else {
                points.add(BlockPos(maxX, minY, z))
                points.add(BlockPos(minX, minY, z))
            }
            leftToRight = !leftToRight
        }
        return points
    }

    private fun finishCurrentPathPass() {
        if (repairMode) {
            verifyRepairPass(forceRescan = true)
        } else {
            startRepairPass()
        }
    }

    private fun startRepairPass() {
        repairMode = true
        repairPass = 0
        completionStableTicks = 0
        verifyRepairPass(forceRescan = true)
    }

    private fun verifyRepairPass(forceRescan: Boolean = false) {
        if (!placingStarted) return
        val missing = getUnplacedBlocks()

        if (missing.isEmpty()) {
            repairBlocks = emptyList()
            completionStableTicks++
            if (completionStableTicks >= COMPLETE_STABLE_TICKS) {
                placingStarted = false
                repairMode = false
                goPoints.clear()
                goPointsIndex = 0
                BaritoneHelper.cancel()
                sendClientMessage("自动放置完成：已连续确认所有方块均已放置")
            }
            return
        }

        completionStableTicks = 0
        if (!forceRescan && missing == repairBlocks) {
            pathToFirstMissingBlock(repairBlocks)
            return
        }

        repairPass++
        repairBlocks = missing
        goPoints = ArrayList(generateSnakeBoundaryPoints(repairBlocks, 4))
        goPointsIndex = 0
        if (goPoints.isEmpty()) {
            pathToFirstMissingBlock(repairBlocks)
        }
        sendClientMessage("修复检查 #$repairPass：发现 ${repairBlocks.size} 个未放置方块，重新巡航补放")
    }

    private fun pathToFirstMissingBlock(blocks: List<PlaceBlock>) {
        val unbuilt = blocks.firstOrNull { block ->
            val actual = mc.level?.getBlockState(block.pos)
            actual != null && actual.block != block.state.block
        } ?: return
        val dist = mc.player?.eyePosition?.distanceTo(Vec3.atCenterOf(unbuilt.pos)) ?: 999.0
        if (dist > 3.0) {
            BaritoneHelper.pathTo(unbuilt.pos)
        }
    }

    private fun activePlaceBlocks(): List<PlaceBlock> {
        return if (repairMode && repairBlocks.isNotEmpty()) repairBlocks else placeBlocks
    }

    private fun getCarpetColorIndex(path: String): Int {
        return when (path) {
            "red_carpet" -> 0
            "magenta_carpet" -> 1
            "pink_carpet" -> 2
            "orange_carpet" -> 3
            "yellow_carpet" -> 4
            "green_carpet" -> 5
            "lime_carpet" -> 6
            "cyan_carpet" -> 7
            "blue_carpet" -> 8
            "light_blue_carpet" -> 9
            "purple_carpet" -> 10
            "black_carpet" -> 11
            "gray_carpet" -> 12
            "light_gray_carpet" -> 13
            "white_carpet" -> 14
            "brown_carpet" -> 15
            else -> -1
        }
    }

    private fun getCarpetColor(index: Int): Color {
        return when (index) {
            0 -> Color.RED
            1 -> Color.MAGENTA
            2 -> Color.PINK
            3 -> Color.ORANGE
            4 -> Color.YELLOW
            5 -> Color.GREEN
            6 -> Color(0, 255, 0)
            7 -> Color.CYAN
            8 -> Color.BLUE
            9 -> Color(173, 216, 230)
            10 -> Color(128, 0, 128)
            11 -> Color.BLACK
            12 -> Color.GRAY
            13 -> Color.LIGHT_GRAY
            14 -> Color.WHITE
            15 -> Color(139, 69, 19)
            else -> Color.WHITE
        }
    }

    private fun getUnplacedBlocks(): List<PlaceBlock> {
        val level = mc.level ?: return emptyList()
        return placeBlocks.filter { block ->
            val actual = level.getBlockState(block.pos)
            actual.block != block.state.block
        }
    }

    private fun isNeededMaterial(block: Block): Boolean {
        val level = mc.level ?: return false
        return placeBlocks.any { b ->
            b.state.block == block && level.getBlockState(b.pos).block != b.state.block
        }
    }

    private fun checkMaterialRefill() {
        val player = mc.player ?: return
        if (!placingStarted || isMaterialRefill) return

        val counts = HashMap<Block, Int>()
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty) {
                val item = stack.item
                if (item is BlockItem) {
                    val block = item.block
                    if (isNeededMaterial(block)) {
                        counts[block] = counts.getOrDefault(block, 0) + stack.count
                    }
                }
            }
        }

        val unplaced = getUnplacedBlocks()
        val neededBlocksInOrder = unplaced.map { it.state.block }.distinct()

        for (block in neededBlocksInOrder) {
            val count = counts.getOrDefault(block, 0)
            if (count < 10) {
                val idx = materialChestMap[block]
                if (idx != null) {
                    val list = chestListPos[idx]
                    for (pair in list) {
                        if (!materialRefillPos.contains(pair.pos1)) {
                            materialRefillPos.add(pair.pos1)
                            isMaterialRefill = true
                        }
                    }
                }
            }
        }
    }

    private fun stillNeedsRefill(): Boolean {
        val player = mc.player ?: return false
        val counts = HashMap<Block, Int>()
        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty) {
                val item = stack.item
                if (item is BlockItem) {
                    val block = item.block
                    if (isNeededMaterial(block)) {
                        counts[block] = counts.getOrDefault(block, 0) + stack.count
                    }
                }
            }
        }
        val unplaced = getUnplacedBlocks()
        val neededBlocksInOrder = unplaced.map { it.state.block }.distinct()

        for (block in neededBlocksInOrder) {
            val count = counts.getOrDefault(block, 0)
            if (count < 10 && materialChestMap.containsKey(block)) {
                val idx = materialChestMap[block]
                if (idx != null) {
                    val list = chestListPos[idx]
                    if (list.any { pair -> materialRefillPos.contains(pair.pos1) }) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun openChest(pos: BlockPos) {
        val player = mc.player ?: return
        val eyePos = player.eyePosition
        val targetVec = Vec3.atCenterOf(pos)

        val rotations = RotationUtil.getRotationsTo(eyePos, targetVec)
        player.setYRot(rotations[0])
        player.setXRot(rotations[1])

        val hit = BlockHitResult(targetVec, Direction.UP, pos, false)
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        player.swing(InteractionHand.MAIN_HAND)
    }

    private fun getInventoryItemCount(block: Block): Int {
        val player = mc.player ?: return 0
        var count = 0
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (!stack.isEmpty) {
                val item = stack.item
                if (item is BlockItem && item.block == block) {
                    count += stack.count
                }
            }
        }
        return count
    }

    private fun moveChestItems(handler: ChestMenu) {
        val player = mc.player ?: return
        if (!chestTransferTimer.passedMs(200)) return
        chestTransferTimer.reset()

        val chestSize = handler.slots.size - 36
        var movedAny = false
        for (slot in 0 until chestSize) {
            val stack = handler.slots[slot].item
            if (!stack.isEmpty) {
                val item = stack.item
                if (item is BlockItem && isNeededMaterial(item.block)) {
                    val currentCount = getInventoryItemCount(item.block)
                    val totalColors = materialChestMap.keys.size.coerceAtLeast(1)
                    val maxAllowedStacks = (27 / totalColors).coerceIn(1, refillStacks.value)
                    val targetCount = maxAllowedStacks * 64
                    if (currentCount < targetCount) {
                        mc.gameMode?.handleInventoryMouseClick(
                            handler.containerId,
                            slot,
                            0,
                            ClickType.QUICK_MOVE,
                            player
                        )
                        movedAny = true
                        break
                    }
                }
            }
        }

        val isChestEmpty = (0 until chestSize).all { handler.slots[it].item.isEmpty }
        val isPlayerInventoryFull = (chestSize until handler.slots.size).none { handler.slots[it].item.isEmpty }

        if (!movedAny || isChestEmpty || isPlayerInventoryFull) {
            player.closeContainer()
            isWaitingForChest = false
            if (materialRefillPos.isNotEmpty()) {
                materialRefillPos.removeFirst()
            }
            if (isPlayerInventoryFull || !stillNeedsRefill()) {
                materialRefillPos.clear()
                isMaterialRefill = false
                if (goPointsIndex > 0) {
                    goPointsIndex--
                }
            }
        }
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private fun BlockState.canClick(level: Level, pos: BlockPos): Boolean {
        return !isAir && !canBeReplaced() && isFaceSturdy(level, pos, Direction.UP)
    }

    private data class PlaceBlock(val pos: BlockPos, val state: BlockState)
    private data class ChestPair(val pos1: BlockPos, val pos2: BlockPos?)

    private companion object {
        const val CACHE_BUILD_BLOCKS_PER_TICK = 20_000
        const val CACHE_SNAPSHOT_INTERVAL_TICKS = 2
        const val BUILT_SCAN_BLOCKS_PER_TICK = 20_000
        const val COMPLETE_STABLE_TICKS = 40
        const val HUD_SCREEN_PADDING = 5f
        const val HUD_INNER_PADDING = 6f
        const val HUD_LINE_HEIGHT = 10f
        const val HUD_TEXT_SCALE = 0.8f
        const val HUD_MAX_TEXT_WIDTH = 160f
        val INFO_PANEL_FILL: Color = Color(18, 20, 26, 185)
        val INFO_MARKER_COLOR: Color = Color(70, 170, 255, 210)
        val INFO_BORDER_FILL = Color(18, 250, 26, 185)

    }
}
