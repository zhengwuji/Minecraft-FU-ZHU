package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.module.anpilot.ANTheme
import anpilot.client.compat.Identifier

class ANWaterMark : ANDraggableHudModule("WaterMark", "客户端Logo与版本水印", "客户端水印", 20f, 10f) {
    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        setHudBounds(scaled(100f), scaled(25f))
        context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(8f), scaled(1.5f), HudColors.panelFillColor, HudColors.panelBorderColor)
        context.imageRect(WATERMARK_TEXTURE, x, y, scaled(100f), scaled(25f), ANTheme.Cyan)
    }

    companion object {
        private val WATERMARK_TEXTURE: Identifier = Identifier("anpilotclient", "textures/icons/logo.png")
    }
}
