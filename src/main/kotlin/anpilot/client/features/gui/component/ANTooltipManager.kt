package anpilot.client.features.gui.component

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.minecraft.gui.MinecraftGuiRenderContext
import java.awt.Color

object ANTooltipManager {
    private var hoveredDescription: String? = null
    private var hoverX: Float = 0f
    private var hoverY: Float = 0f
    private var buttonWidth: Float = 0f

    fun clear() {
        hoveredDescription = null
    }

    fun setTooltip(description: String, x: Float, y: Float, width: Float) {
        hoveredDescription = description
        hoverX = x
        hoverY = y
        buttonWidth = width
    }

    fun renderTooltip(context: ANGuiRenderContext) {
        val desc = hoveredDescription ?: return
        if (desc.isBlank()) return

        val padding = 8f
        val descriptionWidth = context.textWidth(desc).toFloat() + padding * 2f
        val boxWidth = descriptionWidth.coerceAtMost(context.width - 10f)
        val boxX = (hoverX + (buttonWidth - boxWidth) / 2f).coerceIn(4f, context.width - boxWidth - 4f)
        val boxY = (hoverY - 22f).coerceAtLeast(4f)
        val boxHeight = 18f
        val solidDarkBg = Color(0xFF0D1527.toInt(), false) // 100% Solid Opaque Dark Background
        val skyBorder = Color(0xFF60A5FA.toInt(), true) // Sky blue border
        val whiteText = 0xFFFFFFFF.toInt() // Crisp white text with drop shadow

        if (context is MinecraftGuiRenderContext) {
            val pose = context.guiGraphics.pose()
            pose.pushPose()
            pose.translate(0.0, 0.0, 500.0)
            try {
                context.borderedRoundedRect(boxX, boxY, boxWidth, boxHeight, 5f, 1.2f, solidDarkBg, skyBorder)
                context.text(desc, boxX + padding, boxY + 4f, whiteText)
            } finally {
                pose.popPose()
            }
        } else {
            context.borderedRoundedRect(boxX, boxY, boxWidth, boxHeight, 5f, 1.2f, solidDarkBg, skyBorder)
            context.text(desc, boxX + padding, boxY + 4f, whiteText)
        }
    }
}
