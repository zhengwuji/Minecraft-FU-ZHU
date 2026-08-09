package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import net.minecraft.client.Minecraft
import java.awt.Color

class ANFPS : ANDraggableHudModule("FPS", "在屏幕上实时显示客户端运行帧率(FPS)", "游戏帧率", 20f, 40f) {
    private var fpsCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var realFps = 60

    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        val fpsVal = calculateFps()
        val fpsStr = "$fpsVal"
        val labelStr = "FPS "
        val labelWidth = context.textWidth(labelStr).toFloat()
        val valWidth = context.textWidth(fpsStr).toFloat()

        val paddingX = scaled(8f)
        val paddingY = scaled(4f)
        val contentWidth = labelWidth + valWidth
        val totalWidth = contentWidth + paddingX * 2f
        val totalHeight = context.textHeight().toFloat() + paddingY * 2f

        setHudBounds(totalWidth, totalHeight)

        val fpsColor = when {
            fpsVal >= 60 -> Color(0xFF4ADE80.toInt(), true).rgb
            fpsVal >= 30 -> Color(0xFFFACC15.toInt(), true).rgb
            else -> Color(0xFFEF4444.toInt(), true).rgb
        }

        context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(6f), scaled(1.2f), HudColors.panelFillColor, HudColors.panelBorderColor)
        context.text(labelStr, x + paddingX, y + paddingY, HudColors.text1.rgb)
        context.text(fpsStr, x + paddingX + labelWidth, y + paddingY, fpsColor)
    }

    private fun calculateFps(): Int {
        val mcFps = runCatching { Minecraft.getInstance().fps }.getOrDefault(0)
        if (mcFps > 0) return mcFps

        val now = System.currentTimeMillis()
        fpsCount++
        if (now - lastFpsTime >= 1000L) {
            realFps = fpsCount
            fpsCount = 0
            lastFpsTime = now
        }
        return realFps
    }
}
