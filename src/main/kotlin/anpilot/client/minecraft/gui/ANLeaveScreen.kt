package anpilot.client.minecraft.gui

import anpilot.client.features.gui.ANLeaveGuiState
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import java.awt.Color

class ANLeaveScreen : Screen(Component.literal("ANPilot Leave Screen")) {
    private lateinit var gui: MinecraftGuiRenderContext

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        gui = MinecraftGuiRenderContext(graphics, font, width, height)

        val panelWidth = 518f
        val panelHeight = 200f
        val panelX = (width - panelWidth) / 2f
        val panelY = (height - panelHeight) / 2f
        val layout = layout(panelX + 10, panelY)

        drawPanel(panelX, panelY, panelWidth, panelHeight)
        drawHeader(panelX, panelY, panelWidth)
        drawHealthBar(layout)
        drawPlayerInfo(layout, mouseX, mouseY)
        drawInventory(layout, graphics)
        drawNearbyPlayers(layout, graphics)

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawPanel(x: Float, y: Float, width: Float, height: Float) {
        gui.roundedRectWithGlow(
            x,
            y,
            width,
            height,
            20f,
            2f,
            Color(0xE8141B28.toInt(), true),
            Color(0xCC28D3EA.toInt(), true),
            10f,
            Color(0x6628D3EA, true)
        )
    }

    private fun drawHeader(x: Float, y: Float, width: Float) {
        val reason = ANLeaveGuiState.reason
        gui.text(reason, x + 15f, y + 12f, 0xFF50F6FF.toInt(), 1.0f)
        gui.text("Disconnected", x + width - gui.textWidth("Disconnected", 1.0f) - 15f, y + 12f, 0xFFFF6B7A.toInt(), 1.0f)
    }

    private fun drawHealthBar(layout: Layout) {
        val healthX = layout.inventoryX
        val healthWidth = layout.inventoryWidth
        gui.borderedRoundedRect(
            healthX,
            layout.healthY,
            healthWidth,
            layout.healthHeight,
            6f,
            1f,
            Color(0x00000000, true),
            Color(0xCC28D3EA.toInt(), true)
        )
        val healthPercent = (ANLeaveGuiState.health / ANLeaveGuiState.maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        gui.borderedRoundedRect(healthX, layout.healthY, healthWidth * healthPercent, layout.healthHeight, 6f, 1f, Color(0xFFE9435B.toInt(), true), Color(0x00000000.toInt(), true))
    }

    private fun drawPlayerInfo(layout: Layout, mouseX: Int, mouseY: Int) {
        drawInset(layout.playerX, layout.contentY, layout.playerWidth, layout.playerHeight, 14f)
        drawInset(layout.infoX, layout.contentY, layout.infoWidth, layout.infoHeight, 14f)

        val modelSize = layout.playerWidth - 10f
        val modelX = layout.playerX + 5f
        val modelY = layout.contentY + 15f
        ANLeaveGuiState.player?.let { player ->
            gui.playerModel(
                modelX.toInt(),
                modelY.toInt(),
                (modelX + modelSize).toInt(),
                (modelY + modelSize).toInt(),
                32,
                mouseX.toFloat(),
                mouseY.toFloat(),
                player
            )
        }

        val position = parsePosition(ANLeaveGuiState.position)
        gui.text("X: ${position.getOrElse(0) { "Unknown" }}", layout.playerX + 12f, layout.contentY + layout.playerHeight - 50f, 0xFF00FFFF.toInt(), 0.9f)
        gui.text("Y: ${position.getOrElse(1) { "Unknown" }}", layout.playerX + 12f, layout.contentY + layout.playerHeight - 33f, 0xFF00FFFF.toInt(), 0.9f)
        gui.text("Z: ${position.getOrElse(2) { "Unknown" }}", layout.playerX + 12f, layout.contentY + layout.playerHeight - 16f, 0xFF00FFFF.toInt(), 0.9f)

        val textX = layout.infoX + 10f
        val textY = layout.contentY + 5f
        gui.text("Name: ${ANLeaveGuiState.playerName.ifBlank { "Unknown" }}", textX, textY, 0xFFEAF7FF.toInt(), 0.85f)
        gui.text("Ping: ${ANLeaveGuiState.ping}ms", textX, textY + 19f, 0xFFEAF7FF.toInt(), 0.85f)
        gui.text("World: ${ANLeaveGuiState.dimension.ifBlank { "Unknown" }}", textX, textY + 38f, 0xFFEAF7FF.toInt(), 0.85f)
    }

    private fun drawInventory(layout: Layout, graphics: GuiGraphics) {
        drawInset(layout.inventoryX, layout.inventoryY, layout.inventoryWidth, layout.inventoryHeight, 14f)
        drawInset(layout.inventoryX, layout.hotbarY, layout.inventoryWidth, layout.hotbarHeight, 14f)

        val padding = layout.itemPadding
        val cols = 9
        val rows = 3
        val slotSize = layout.slotSize
        val itemSize = layout.itemSize
        val startX = layout.inventoryX + padding
        val startY = layout.inventoryY + padding

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val sourceSlot = row * cols + col + 9
                drawItem(graphics, itemAt(sourceSlot), startX + col * slotSize, startY + row * slotSize, itemSize)
            }
        }

        val hotbarX = layout.inventoryX + padding
        val hotbarItemsY = layout.hotbarY + padding
        for (col in 0 until cols) {
            drawItem(graphics, itemAt(col), hotbarX + col * slotSize, hotbarItemsY, itemSize)
        }

        val armorY = hotbarItemsY + slotSize
        ANLeaveGuiState.armor.take(4).forEachIndexed { index, stack ->
            drawItem(graphics, stack, hotbarX + index * slotSize, armorY, itemSize)
        }
    }

    private fun drawNearbyPlayers(layout: Layout, graphics: GuiGraphics) {
        drawInset(layout.infoX, layout.nearbyY, layout.infoWidth, layout.nearbyHeight, 12f)

        ANLeaveGuiState.nearbyPlayers.take(5).forEachIndexed { index, player ->
            val rowY = layout.nearbyY + 5f + index * 15f
            player.skin?.let { skin ->
                drawHead(graphics, skin, layout.infoX + 10f, rowY, 10f)
            }
            gui.text(player.name, layout.infoX + 28f, rowY, 0xFF00FFFF.toInt(), 0.85f)
        }
    }

    private fun drawInset(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        gui.borderedRoundedRect(
            x,
            y,
            width,
            height,
            radius,
            1f,
            Color(0x77101825, true),
            Color(0x6644E7FF, true)
        )
    }

    private fun drawItem(graphics: GuiGraphics, stack: ItemStack, x: Float, y: Float, size: Float = 16f) {
        if (stack.isEmpty) return
        val itemX = x.toInt()
        val itemY = y.toInt()
        graphics.pose().pushPose()
        val scale = size / 16f
        graphics.pose().translate(itemX.toFloat(), itemY.toFloat(), 0f)
        graphics.pose().scale(scale, scale, 1f)
        graphics.renderItem(stack.copy(), 0, 0)
        graphics.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0)
        graphics.pose().popPose()
    }

    private fun drawHead(graphics: GuiGraphics, texture: ResourceLocation, x: Float, y: Float, size: Float) {
        val drawSize = size.toInt()
        val drawX = x.toInt()
        val drawY = y.toInt()
        graphics.blit(texture, drawX, drawY, 8f, 8f, drawSize, drawSize, 64, 64)
        graphics.blit(texture, drawX, drawY, 40f, 8f, drawSize, drawSize, 64, 64)
    }

    private fun layout(x: Float, y: Float): Layout {
        val padding = 5f
        val gap = 8f
        val headerHeight = 40f
        val healthHeight = 14f
        val healthY = y + headerHeight
        val contentY = y + headerHeight
        val slotSize = 24f
        val itemSize = 20f
        val inventoryWidth = slotSize * 9f + padding * 2f
        val inventoryHeight = slotSize * 3f + padding * 2f
        val hotbarHeight = slotSize + padding * 2f
        val playerWidth = 80f
        val infoWidth = 150f
        val playerHeight = 145f
        val infoHeight = 60f
        val nearbyHeight = 78f
        val playerX = x + 5f
        val infoX = playerX + playerWidth + 2 * gap
        val inventoryX = infoX + infoWidth + 2 * gap
        val inventoryY = healthY + healthHeight + gap
        val hotbarY = inventoryY + inventoryHeight + gap
        val nearbyY = contentY + infoHeight + gap
        return Layout(
            playerX,
            infoX,
            inventoryX,
            inventoryY,
            contentY,
            healthY,
            nearbyY,
            hotbarY,
            playerWidth,
            playerHeight,
            infoWidth,
            inventoryWidth,
            infoHeight,
            nearbyHeight,
            inventoryHeight,
            hotbarHeight,
            healthHeight,
            padding,
            slotSize,
            itemSize
        )
    }

    private fun parsePosition(position: String): List<String> {
        return position.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun itemAt(slot: Int): ItemStack {
        return ANLeaveGuiState.inventory.getOrNull(slot) ?: ItemStack.EMPTY
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false

    private data class Layout(
        val playerX: Float,
        val infoX: Float,
        val inventoryX: Float,
        val inventoryY: Float,
        val contentY: Float,
        val healthY: Float,
        val nearbyY: Float,
        val hotbarY: Float,
        val playerWidth: Float,
        val playerHeight: Float,
        val infoWidth: Float,
        val inventoryWidth: Float,
        val infoHeight: Float,
        val nearbyHeight: Float,
        val inventoryHeight: Float,
        val hotbarHeight: Float,
        val healthHeight: Float,
        val itemPadding: Float,
        val slotSize: Float,
        val itemSize: Float
    )
}
