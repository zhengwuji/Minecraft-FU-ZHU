package anpilot.client.renderer.font

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import kotlin.math.roundToInt

class ANFontRenderer(private val fallbackFont: Font) {

    fun draw(context: GuiGraphics, text: String, x: Float, y: Float, color: Int, scale: Float = 1f, scissorArea: ScreenRectangle? = null) {
        if (x.isNaN() || y.isNaN()) return
        context.drawString(fallbackFont, text, x.roundToInt(), y.roundToInt(), color, true)
    }

    fun drawCentered(context: GuiGraphics, text: String, x: Int, y: Int, color: Int, scissorArea: ScreenRectangle? = null) {
        draw(context, text, (x - width(text) / 2f).roundToInt().toFloat(), y.toFloat(), color, 1f, scissorArea)
    }

    fun drawCentered(context: GuiGraphics, text: String, x: Int, y: Int, color: Int, scale: Float = 1f, scissorArea: ScreenRectangle? = null) {
        draw(context, text, (x - width(text) / 2f).roundToInt().toFloat(), y.toFloat(), color, 1f, scissorArea)
    }

    fun width(text: String, scale: Float = 1f): Int = fallbackFont.width(text)

    fun height(scale: Float = 1f): Int = fallbackFont.lineHeight

    fun customFontLoaded(): Boolean = false
}
