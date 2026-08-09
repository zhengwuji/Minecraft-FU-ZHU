package anpilot.client.minecraft.gui

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.renderer.ANGUIRenderer
import anpilot.client.renderer.font.ANFontRenderer
import anpilot.client.renderer.render.ANProceduralDecorRenderer
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import anpilot.client.compat.PlayerSkin
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.Minecraft

class MinecraftGuiRenderContext(
    val guiGraphics: GuiGraphics,
    font: Font,
    override val width: Int,
    override val height: Int
) : ANGuiRenderContext {
    val context: GuiGraphics get() = guiGraphics
    private val fontRenderer = sharedFontRenderer ?: ANFontRenderer(font).also { sharedFontRenderer = it }
    private val scissorStack = ArrayDeque<ScreenRectangle>()

    override val scissorArea: ScreenRectangle?
        get() = scissorStack.lastOrNull()

    override fun blur(x: Float, y: Float, width: Float, height: Float, radius: Float, tint: Color) {
        ANGUIRenderer.blur(context, x, y, width, height, radius, tint, scissorArea)
    }

    override fun blur(x: Float, y: Float, width: Float, height: Float, cornerRadius: Float, blurRadius: Float, tint: Color) {
        ANGUIRenderer.blur(context, x, y, width, height, cornerRadius, blurRadius, tint, scissorArea)
    }

    override fun rect(x: Float, y: Float, width: Float, height: Float, color: Color) {
        ANGUIRenderer.rect(context, x, y, width, height, color)
    }

    override fun imageRect(texture: ResourceLocation, x: Float, y: Float, width: Float, height: Float, color: Color) {
        ANGUIRenderer.imageRect(context, texture, x, y, width, height, color, scissorArea)
    }

    override fun roundedImageRect(texture: ResourceLocation, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) {
        ANGUIRenderer.roundedImageRect(context, texture, x, y, width, height, radius, color, scissorArea)
    }

    override fun head(skin: PlayerSkin, x: Float, y: Float, size: Float, color: Color) {
        val drawSize = size.toInt()
        val texture = skin.texture
        context.blit(texture, x.toInt(), y.toInt(), 8f, 8f, drawSize, drawSize, 64, 64)
        context.blit(texture, x.toInt(), y.toInt(), 40f, 8f, drawSize, drawSize, 64, 64)
    }

    override fun playerModel(x: Int, y: Int, width: Int, height: Int, size: Int, mouseX: Float, mouseY: Float, entity: LivingEntity) {
        ANGUIRenderer.playerModel(context, x, y, width, height, size, mouseX, mouseY, entity)
    }

    override fun item(stack: ItemStack, x: Float, y: Float, scale: Float, decorations: Boolean) {
        if (stack.isEmpty) return
        context.pose().pushPose()
        context.pose().translate(x.toDouble(), y.toDouble(), 0.0)
        context.pose().scale(scale, scale, 1.0f)
        context.renderItem(stack.copy(), 0, 0)
        if (decorations) context.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0)
        context.pose().popPose()
    }

    override fun gradientRect(x: Float, y: Float, width: Float, height: Float, topLeftColor: Color, topRightColor: Color, bottomRightColor: Color, bottomLeftColor: Color) {
        ANGUIRenderer.gradientRect(context, x, y, width, height, topLeftColor, topRightColor, bottomRightColor, bottomLeftColor, scissorArea)
    }

    override fun roundedGradientRect(x: Float, y: Float, width: Float, height: Float, radius: Float, topLeftColor: Color, topRightColor: Color, bottomRightColor: Color, bottomLeftColor: Color) {
        ANGUIRenderer.roundedGradientRect(context, x, y, width, height, radius, topLeftColor, topRightColor, bottomRightColor, bottomLeftColor, scissorArea)
    }

    override fun roundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) {
        ANGUIRenderer.roundedRect(context, x, y, width, height, radius, color, scissorArea)
    }

    override fun borderedRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, borderWidth: Float, fillColor: Color, borderColor: Color) {
        ANGUIRenderer.borderedRoundedRect(context, x, y, width, height, radius, borderWidth, fillColor, borderColor, scissorArea)
    }

    override fun glowingRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color, glowRadius: Float, glowColor: Color) {
        ANGUIRenderer.glowingRoundedRect(context, x, y, width, height, radius, color, glowRadius, glowColor, scissorArea)
    }

    override fun roundedRectWithGlow(x: Float, y: Float, width: Float, height: Float, radius: Float, borderWidth: Float, fillColor: Color, borderColor: Color, glowRadius: Float, glowColor: Color) {
        ANGUIRenderer.roundedRectWithGlow(context, x, y, width, height, radius, borderWidth, fillColor, borderColor, glowRadius, glowColor, scissorArea)
    }

    override fun roundedBorderDecor(
        texture: ResourceLocation,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        options: ANProceduralDecorRenderer.RoundedBorderDecorOptions
    ) {
        ANGUIRenderer.roundedBorderDecor(context, texture, x, y, width, height, radius, options, scissorArea)
    }

    override fun text(text: String, x: Float, y: Float, color: Int) {
        fontRenderer.draw(context, text, x, y, color, scissorArea = scissorArea)
    }

    override fun text(text: String, x: Float, y: Float, color: Int, scale: Float) {
        fontRenderer.draw(context, text, x, y, color, scale, scissorArea)
    }

    override fun centeredText(text: String, x: Int, y: Int, color: Int) {
        fontRenderer.drawCentered(context, text, x, y, color, scissorArea)
    }

    override fun centeredText(text: String, x: Int, y: Int, color: Int, scale: Float) {
        fontRenderer.drawCentered(context, text, x, y, color, scale, scissorArea)
    }

    override fun textWidth(text: String): Int = fontRenderer.width(text)

    override fun textWidth(text: String, scale: Float): Int = fontRenderer.width(text, scale)

    override fun textHeight(): Int = fontRenderer.height()

    override fun textHeight(scale: Float): Int = fontRenderer.height(scale)

    override fun pushScissor(x: Float, y: Float, width: Float, height: Float) {
        val parent = scissorArea
        val left = max(x, parent?.left()?.toFloat() ?: x).toInt()
        val top = max(y, parent?.top()?.toFloat() ?: y).toInt()
        val right = min(x + width, parent?.right()?.toFloat() ?: (x + width)).toInt()
        val bottom = min(y + height, parent?.bottom()?.toFloat() ?: (y + height)).toInt()
        val scissor = ScreenRectangle(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
        scissorStack.addLast(scissor)
        context.enableScissor(scissor.left(), scissor.top(), scissor.right(), scissor.bottom())
    }

    override fun popScissor() {
        if (scissorStack.isNotEmpty()) scissorStack.removeLast()
        context.disableScissor()
        scissorArea?.let { context.enableScissor(it.left(), it.top(), it.right(), it.bottom()) }
    }

    private companion object {
        private var sharedFontRenderer: ANFontRenderer? = null
    }
}
