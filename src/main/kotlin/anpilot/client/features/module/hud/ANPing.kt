package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import net.minecraft.client.Minecraft
import java.awt.Color

class ANPing : ANDraggableHudModule("Ping", "当前客户端与服务器之间的网络延迟(Ping)", "服务器延迟", 20f, 65f) {
    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        val pingVal = getPing()
        val pingStr = "${pingVal}ms"
        val labelStr = "Ping "
        val labelWidth = context.textWidth(labelStr).toFloat()
        val valWidth = context.textWidth(pingStr).toFloat()

        val paddingX = scaled(8f)
        val paddingY = scaled(4f)
        val contentWidth = labelWidth + valWidth
        val totalWidth = contentWidth + paddingX * 2f
        val totalHeight = context.textHeight().toFloat() + paddingY * 2f

        setHudBounds(totalWidth, totalHeight)

        val pingColor = when {
            pingVal <= 80 -> Color(0xFF4ADE80.toInt(), true).rgb
            pingVal <= 180 -> Color(0xFFFACC15.toInt(), true).rgb
            else -> Color(0xFFEF4444.toInt(), true).rgb
        }

        context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(6f), scaled(1.2f), HudColors.panelFillColor, HudColors.panelBorderColor)
        context.text(labelStr, x + paddingX, y + paddingY, HudColors.text1.rgb)
        context.text(pingStr, x + paddingX + labelWidth, y + paddingY, pingColor)
    }

    private fun getPing(): Int {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return 0
        return minecraft.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
    }
}
