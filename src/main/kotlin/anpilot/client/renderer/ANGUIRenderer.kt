package anpilot.client.renderer

import anpilot.client.renderer.render.ANRender2DEngine
import anpilot.client.renderer.render.ANProceduralDecorRenderer
import anpilot.client.compat.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import anpilot.client.compat.Identifier
import net.minecraft.world.entity.LivingEntity
import java.awt.Color

object ANGUIRenderer {
    fun blur(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        tint: Color = Color(255, 255, 255, 255),
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.blur(context, x, y, width, height, radius, tint.rgb, scissorArea)
    }

    fun blur(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cornerRadius: Float,
        blurRadius: Float,
        tint: Color = Color(255, 255, 255, 255),
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.blur(context, x, y, width, height, cornerRadius, blurRadius, tint.rgb, scissorArea)
    }

    fun rect(context: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, color: Color) {
        ANRender2DEngine.rect(context, x, y, width, height, color.rgb)
    }

    fun imageRect(context: GuiGraphicsExtractor, texture: Identifier, x: Float, y: Float, width: Float, height: Float, color: Color, scissorArea: ScreenRectangle? = null) {
        ANRender2DEngine.imageRect(context, texture, x, y, width, height, color.rgb, scissorArea)
    }

    fun roundedImageRect(context: GuiGraphicsExtractor, texture: Identifier, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color, scissorArea: ScreenRectangle? = null) {
        ANRender2DEngine.roundedImageRect(context, texture, x, y, width, height, radius, color.rgb, scissorArea)
    }

    fun roundedBorderDecor(
        context: GuiGraphicsExtractor,
        texture: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        options: ANProceduralDecorRenderer.RoundedBorderDecorOptions,
        scissorArea: ScreenRectangle? = null
    ) {
        ANProceduralDecorRenderer.roundedBorderDecor(context, texture, x, y, width, height, radius, options, scissorArea)
    }

    fun imageRect(
        context: GuiGraphicsExtractor,
        texture: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Color,
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.imageRect(context, texture, x, y, width, height, u0, v0, u1, v1, color.rgb, scissorArea)
    }

    fun gradientRect(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        topLeftColor: Color,
        topRightColor: Color,
        bottomRightColor: Color,
        bottomLeftColor: Color,
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.gradientRect(context, x, y, width, height, topLeftColor.rgb, topRightColor.rgb, bottomRightColor.rgb, bottomLeftColor.rgb, scissorArea)
    }

    fun roundedGradientRect(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        topLeftColor: Color,
        topRightColor: Color,
        bottomRightColor: Color,
        bottomLeftColor: Color,
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.roundedGradientRect(context, x, y, width, height, radius, topLeftColor.rgb, topRightColor.rgb, bottomRightColor.rgb, bottomLeftColor.rgb, scissorArea)
    }

    fun roundedRect(context: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color, scissorArea: ScreenRectangle? = null) {
        ANRender2DEngine.roundedRect(context, x, y, width, height, radius, color.rgb, scissorArea)
    }

    fun borderedRoundedRect(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        borderWidth: Float,
        fillColor: Color,
        borderColor: Color,
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.borderedRoundedRect(context, x, y, width, height, radius, borderWidth, fillColor.rgb, borderColor.rgb, scissorArea)
    }

    fun glowingRoundedRect(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Color,
        glowRadius: Float,
        glowColor: Color,
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.glowingRoundedRect(context, x, y, width, height, radius, color.rgb, glowRadius, glowColor.rgb, scissorArea)
    }

    fun roundedRectWithGlow(
        context: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        borderWidth: Float,
        fillColor: Color,
        borderColor: Color,
        glowRadius: Float,
        glowColor: Color,
        scissorArea: ScreenRectangle? = null
    ) {
        ANRender2DEngine.roundedRectWithGlow(
            context,
            x,
            y,
            width,
            height,
            radius,
            borderWidth,
            fillColor.rgb,
            borderColor.rgb,
            glowRadius,
            glowColor.rgb,
            scissorArea
        )
    }

    fun playerModel(
        context: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        size: Int,
        mouseX: Float,
        mouseY: Float,
        entity: LivingEntity
    ) {
        InventoryScreen.renderEntityInInventoryFollowsMouse(context, x + width / 2, y + height, size, (x + width / 2).toFloat() - mouseX, (y + height / 2).toFloat() - mouseY, entity)
    }
}
