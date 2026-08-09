package anpilot.client.renderer.render

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.resources.ResourceLocation
import kotlin.math.roundToInt

object ANRender2DEngine {
    fun blur(context: GuiGraphics, x: Float, y: Float, width: Float, height: Float, radius: Float, tintColor: Int, scissorArea: ScreenRectangle? = null) {
        rect(context, x, y, width, height, tintColor)
    }

    fun blur(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cornerRadius: Float,
        blurRadius: Float,
        tintColor: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        rect(context, x, y, width, height, tintColor)
    }

    fun rect(context: GuiGraphics, x: Float, y: Float, width: Float, height: Float, color: Int) {
        context.fill(x.roundToInt(), y.roundToInt(), (x + width).roundToInt(), (y + height).roundToInt(), color)
    }

    fun imageRect(context: GuiGraphics, texture: ResourceLocation, x: Float, y: Float, width: Float, height: Float, color: Int, scissorArea: ScreenRectangle? = null) {
        context.blit(texture, x.roundToInt(), y.roundToInt(), 0f, 0f, width.roundToInt(), height.roundToInt(), width.roundToInt(), height.roundToInt())
    }

    fun imageRect(
        context: GuiGraphics,
        texture: ResourceLocation,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        context.blit(texture, x.roundToInt(), y.roundToInt(), u0, v0, width.roundToInt(), height.roundToInt(), width.roundToInt(), height.roundToInt())
    }

    fun roundedImageRect(
        context: GuiGraphics,
        texture: ResourceLocation,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        imageRect(context, texture, x, y, width, height, color, scissorArea)
    }

    fun roundedRect(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        rect(context, x, y, width, height, color)
    }

    fun borderedRoundedRect(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        borderWidth: Float,
        fillColor: Int,
        borderColor: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        rect(context, x, y, width, height, borderColor)
        rect(context, x + borderWidth, y + borderWidth, width - borderWidth * 2f, height - borderWidth * 2f, fillColor)
    }

    fun glowingRoundedRect(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        glowRadius: Float,
        glowColor: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        rect(context, x - glowRadius, y - glowRadius, width + glowRadius * 2f, height + glowRadius * 2f, glowColor)
        rect(context, x, y, width, height, color)
    }

    fun roundedRectWithGlow(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        borderWidth: Float,
        fillColor: Int,
        borderColor: Int,
        glowRadius: Float,
        glowColor: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        rect(context, x - glowRadius, y - glowRadius, width + glowRadius * 2f, height + glowRadius * 2f, glowColor)
        borderedRoundedRect(context, x, y, width, height, radius, borderWidth, fillColor, borderColor, scissorArea)
    }

    fun gradientRect(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        topLeftColor: Int,
        topRightColor: Int,
        bottomRightColor: Int,
        bottomLeftColor: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        context.fillGradient(x.roundToInt(), y.roundToInt(), (x + width).roundToInt(), (y + height).roundToInt(), topLeftColor, bottomRightColor)
    }

    fun roundedGradientRect(
        context: GuiGraphics,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        topLeftColor: Int,
        topRightColor: Int,
        bottomRightColor: Int,
        bottomLeftColor: Int,
        scissorArea: ScreenRectangle? = null
    ) {
        gradientRect(context, x, y, width, height, topLeftColor, topRightColor, bottomRightColor, bottomLeftColor, scissorArea)
    }

    fun roundedBorderDecor(
        context: GuiGraphics,
        texture: ResourceLocation,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        options: ANProceduralDecorRenderer.RoundedBorderDecorOptions,
        scissorArea: ScreenRectangle? = null
    ) {
        rect(context, x, y, width, height, options.color)
    }
}
