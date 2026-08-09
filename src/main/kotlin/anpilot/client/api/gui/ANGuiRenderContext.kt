package anpilot.client.api.gui

import net.minecraft.client.gui.navigation.ScreenRectangle
import anpilot.client.renderer.render.ANProceduralDecorRenderer
import anpilot.client.compat.Identifier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import anpilot.client.compat.PlayerSkin
import java.awt.Color

interface ANGuiRenderContext {
    val width: Int
    val height: Int
    val scissorArea: ScreenRectangle?

    fun blur(x: Float, y: Float, width: Float, height: Float, radius: Float, tint: Color = Color(255, 255, 255, 255))

    fun blur(x: Float, y: Float, width: Float, height: Float, cornerRadius: Float, blurRadius: Float, tint: Color = Color(255, 255, 255, 255))

    fun rect(x: Float, y: Float, width: Float, height: Float, color: Color)

    fun imageRect(texture: Identifier, x: Float, y: Float, width: Float, height: Float, color: Color)

    fun roundedImageRect(texture: Identifier, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color)

    fun head(skin: PlayerSkin, x: Float, y: Float, size: Float, color: Color = Color.WHITE)

    fun playerModel(x: Int, y: Int, width: Int, height: Int, size: Int, mouseX: Float, mouseY: Float, entity: LivingEntity)

    fun item(stack: ItemStack, x: Float, y: Float, scale: Float = 1f, decorations: Boolean = false)

    fun gradientRect(x: Float, y: Float, width: Float, height: Float, topLeftColor: Color, topRightColor: Color, bottomRightColor: Color, bottomLeftColor: Color)

    fun roundedGradientRect(x: Float, y: Float, width: Float, height: Float, radius: Float, topLeftColor: Color, topRightColor: Color, bottomRightColor: Color, bottomLeftColor: Color)

    fun roundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color)

    fun borderedRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, borderWidth: Float, fillColor: Color, borderColor: Color)

    fun glowingRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color, glowRadius: Float, glowColor: Color)

    fun roundedRectWithGlow(x: Float, y: Float, width: Float, height: Float, radius: Float, borderWidth: Float, fillColor: Color, borderColor: Color, glowRadius: Float, glowColor: Color)

    fun roundedBorderDecor(texture: Identifier, x: Float, y: Float, width: Float, height: Float, radius: Float, options: ANProceduralDecorRenderer.RoundedBorderDecorOptions)

    fun text(text: String, x: Float, y: Float, color: Int)

    fun text(text: String, x: Float, y: Float, color: Int, scale: Float)

    fun centeredText(text: String, x: Int, y: Int, color: Int)

    fun centeredText(text: String, x: Int, y: Int, color: Int, scale: Float)

    fun textWidth(text: String): Int

    fun textWidth(text: String, scale: Float): Int

    fun textHeight(): Int

    fun textHeight(scale: Float): Int

    fun pushScissor(x: Float, y: Float, width: Float, height: Float)

    fun popScissor()
}
