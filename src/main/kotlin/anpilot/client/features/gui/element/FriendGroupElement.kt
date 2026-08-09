package anpilot.client.features.gui.element

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.gui.component.ANElement
import anpilot.client.features.manager.ANFriendManager
import anpilot.client.features.module.anpilot.ANTheme
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.FriendGroupSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import anpilot.client.compat.PlayerSkin
import java.awt.Color
import java.util.UUID

class FriendGroupElement(
    private val setting: ANSetting<FriendGroupSetting>
) : ANElement(height = 118f) {
    private companion object {
        private const val ROWS = 5
        private const val ROW_HEIGHT = 18f
        private const val ROW_GAP = 2f
        private const val TEXT_SCALE = 0.62f

        private val ADD_FILL = Color(57, 57, 57, 197)
        private val ADD_BORDER = Color(17, 108, 100, 255)
        private val ADD_ICON = Color(0, 0, 255, 255)
        private val ADD_ICON_HOVER = Color(255, 3, 79, 255)
    }

    private var index = 1
    private var pageTextWidth = 30f

    override fun render(context: ANGuiRenderContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val totalPages = if (ANFriendManager.pilotFriends.isEmpty()) 1 else (ANFriendManager.pilotFriends.size - 1) / ROWS + 1
        if (index > totalPages) {
            index = totalPages
        }
        if (index < 1) {
            index = 1
        }

        height = 118f

        for (row in 0 until ROWS) {
            val i = (index - 1) * ROWS + row
            val offset = row * (ROW_HEIGHT + ROW_GAP)
            val rowY = y + offset

            if (i < ANFriendManager.pilotFriends.size) {
                val name = ANFriendManager.pilotFriends[i]
                renderFriendRow(context, mouseX, mouseY, rowY, name)
            } else {
                renderAddRow(context, mouseX, mouseY, rowY)
            }
        }

        renderIndexBlock(context, mouseX, mouseY, totalPages)
    }

    private fun renderFriendRow(context: ANGuiRenderContext, mouseX: Int, mouseY: Int, rowY: Float, name: String) {
        val hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT
        val fill = if (hovered) ANTheme.SelHoverFill else ANTheme.SelFill
        val border = if (hovered) ANTheme.SetAccent else ANTheme.SelBorder

        context.borderedRoundedRect(x, rowY, width, ROW_HEIGHT, 5f, 1f, fill, border)

        
        val connection = Minecraft.getInstance().connection
        val entry = connection?.getPlayerInfo(name)
        if (entry != null) {
            context.head(PlayerSkin(entry.skinLocation), x + 4f, rowY + 3f, 12f)
        } else {
            context.head(PlayerSkin(DefaultPlayerSkin.getDefaultSkin()), x + 4f, rowY + 3f, 12f)
        }

        
        val textY = rowY + (ROW_HEIGHT - context.textHeight(TEXT_SCALE)) / 2f
        context.text(name, x + 20f, textY, ANTheme.SetText.rgb, TEXT_SCALE)

        
        if (hovered) {
            val deleteHovered = mouseX >= x + width - 16f && mouseX <= x + width - 4f && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT
            val crossY = (rowY + (ROW_HEIGHT - context.textHeight(0.6f)) / 2f).toInt()
            if (deleteHovered) {
                context.roundedRect(x + width - 16f, rowY + 3f, 12f, 12f, 3f, Color(255, 50, 50, 200))
                context.centeredText("x", (x + width - 10f).toInt(), crossY, Color.WHITE.rgb, 0.6f)
            } else {
                context.centeredText("x", (x + width - 10f).toInt(), crossY, Color(200, 50, 50, 150).rgb, 0.6f)
            }
        }
    }

    private fun renderAddRow(context: ANGuiRenderContext, mouseX: Int, mouseY: Int, rowY: Float) {
        val hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT
        val fill = if (hovered) ANTheme.SelHoverFill else ADD_FILL
        val border = if (hovered) ANTheme.SetAccent else ADD_BORDER
        context.borderedRoundedRect(x, rowY, width, ROW_HEIGHT, 5f, 1f, fill, border)

        val plusText = "+"
        val plusWidth = context.textWidth(plusText, TEXT_SCALE)
        val plusX = x + (width - plusWidth) / 2f
        val plusY = rowY + (ROW_HEIGHT - context.textHeight(TEXT_SCALE)) / 2f
        val plusColor = if (hovered) ADD_ICON_HOVER.rgb else ADD_ICON.rgb
        context.text(plusText, plusX, plusY, plusColor, TEXT_SCALE)
    }

    private fun renderIndexBlock(context: ANGuiRenderContext, mouseX: Int, mouseY: Int, totalPages: Int) {
        val indexX = x + (width - 100f) / 2f
        val indexY = y + 100f

        val center = indexX + 50f
        val pageText = "$index | $totalPages"
        val textWidth = context.textWidth(pageText, TEXT_SCALE).toFloat()
        pageTextWidth = textWidth
        val gap = 8f

        val leftArrowX = center - pageTextWidth / 2f - gap
        val rightArrowX = center + pageTextWidth / 2f + gap

        val hoverDown = mouseX >= leftArrowX - 8f && mouseX <= leftArrowX + 8f && mouseY >= indexY && mouseY <= indexY + ROW_HEIGHT
        val hoverUp = mouseX >= rightArrowX - 8f && mouseX <= rightArrowX + 8f && mouseY >= indexY && mouseY <= indexY + ROW_HEIGHT

        val arrowY = (indexY + (ROW_HEIGHT - context.textHeight(TEXT_SCALE)) / 2f).toInt()
        val arrowDownColor = if (hoverDown) ANTheme.SetText.rgb else ANTheme.SetAccent.rgb
        val arrowUpColor = if (hoverUp) ANTheme.SetText.rgb else ANTheme.SetAccent.rgb

        context.centeredText("<", leftArrowX.toInt(), arrowY, arrowDownColor, TEXT_SCALE)
        context.centeredText(">", rightArrowX.toInt(), arrowY, arrowUpColor, TEXT_SCALE)

        context.centeredText(pageText, center.toInt(), arrowY, ANTheme.SetText.rgb, TEXT_SCALE)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 && button != 2) return false

        val totalPages = if (ANFriendManager.pilotFriends.isEmpty()) 1 else (ANFriendManager.pilotFriends.size - 1) / ROWS + 1
        val indexX = x + (width - 100f) / 2f
        val indexY = y + 100f

        val center = indexX + 50f
        val gap = 8f
        val leftArrowX = center - pageTextWidth / 2f - gap
        val rightArrowX = center + pageTextWidth / 2f + gap

        val hoverDown = mouseX >= leftArrowX - 8f && mouseX <= leftArrowX + 8f && mouseY >= indexY && mouseY <= indexY + ROW_HEIGHT
        val hoverUp = mouseX >= rightArrowX - 8f && mouseX <= rightArrowX + 8f && mouseY >= indexY && mouseY <= indexY + ROW_HEIGHT

        if (button == 0) {
            if (hoverDown) {
                index = (index - 1).coerceAtLeast(1)
                return true
            }
            if (hoverUp) {
                index = (index + 1).coerceAtMost(totalPages)
                return true
            }
        }

        for (row in 0 until ROWS) {
            val i = (index - 1) * ROWS + row
            val offset = row * (ROW_HEIGHT + ROW_GAP)
            val rowY = y + offset
            val isHovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT
            if (!isHovered) continue

            if (i < ANFriendManager.pilotFriends.size) {
                val name = ANFriendManager.pilotFriends[i]
                if (button == 2) {
                    ANFriendManager.removeFriend(name)
                    return true
                }
                val deleteHovered = mouseX >= x + width - 16f && mouseX <= x + width - 4f
                if (button == 0 && deleteHovered) {
                    ANFriendManager.removeFriend(name)
                    return true
                }
            }
        }

        return false
    }
}

