package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.item.ItemEntity
import java.awt.Color

class ANDropsESP : ANBaseModule(
    name = "DropsESP",
    description = "穿墙透视高亮周围地面掉落物",
    category = ANModuleCategory.RENDER,
    chineseName = "掉落物透视"
), ANWorldRenderModule {
    val scale = addSetting(ANSetting("Scale", 1.5f, 0.5f, 4f))
    val bobbing = addSetting(ANSetting("Bobbing", true))
    val tracers = addSetting(ANSetting("TracerLine", false))
    val outline = addSetting(ANSetting("Outline", true))
    val color = addSetting(ANSetting("Color", ColorGroupSetting(Color(0x9935FA1F.toInt(), true).rgb)))
    val outlineColor = addSetting(ANSetting("OutlineColor", ColorGroupSetting(Color(0xFF35FA1F.toInt(), true).rgb)))

    override fun renderWorld(context: LevelRenderContext) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (!tracers.value) return

        val lineColor = color.value.toANColor().withAlpha(255)
        val from = ANRender3DEngine.crosshairWorldPos(context)
        for (entity in level.entitiesForRendering()) {
            val item = entity as? ItemEntity ?: continue
            if (!item.isAlive) continue
            ANRender3DEngine.line(context, from, item.boundingBox.center, lineColor)
        }
    }

    fun tintColor(): Int = color.value.getColor()

    fun outlineColorInt(): Int {
        val color = outlineColor.value.toANColor()
        return Color(color.red, color.green, color.blue, 255).rgb
    }

    fun bobbingEnabled(): Boolean = bobbing.value

    fun scaleFor(distance: Double): Float {
        val baseScale = scale.value
        val factor = (1.0 + distance * 0.02).coerceAtMost(5.0)
        return (baseScale * factor).toFloat()
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    companion object {
        @JvmField
        var renderingDroppedItem = false
    }
}
