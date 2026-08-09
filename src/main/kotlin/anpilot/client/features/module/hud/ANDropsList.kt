package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.setting.impl.ItemSelectSetting
import net.minecraft.client.Minecraft
import anpilot.client.compat.Identifier
import net.minecraft.world.entity.item.ItemEntity
import java.awt.Color

class ANDropsList : ANDraggableHudModule("DropsList", "在HUD上按距离列出周围出现的掉落物及数量", "掉落物列表", 20f, 110f) {
    val highlighted = addSetting(ANSetting("Items", ItemSelectSetting(ArrayList())))
    val highlightColor = addSetting(ANSetting("HighlightColor", ColorGroupSetting(Color(0xFFD128EA.toInt(), true).rgb)))

    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        val counts = linkedMapOf<String, Pair<String, Int>>()
        Minecraft.getInstance().level?.entitiesForRendering()?.forEach { entity ->
            val itemEntity = entity as? ItemEntity ?: return@forEach
            val stack = itemEntity.item
            val key = stack.item.descriptionId.removePrefix("item.minecraft.").removePrefix("block.minecraft.")
            val name = stack.hoverName.string
            val current = counts[key]?.second ?: 0
            counts[key] = name to current + stack.count
        }
        setHudBounds(scaled(110f), scaled(300f))
        context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(10f), scaled(1f), HudColors.panelFillColor, HudColors.panelBorderColor)
        context.imageRect(DROPS_TEXTURE, x + scaled(5f), y + scaled(5f), scaled(80f), scaled(22f), HudColors.text3)
        var offset = 0f
        counts.forEach { (key, pair) ->
            val line = pair.first + " x" + pair.second
            val color = if (highlighted.value.contains(key)) highlightColor.value.getColor() else HudColors.text2.rgb
            context.text(line, x + scaled(10f), y + scaled(30f) + offset, color, hudScale)
            offset += scaled(15f)
        }
        if (counts.isEmpty() && editor) context.text("DropsList", x + scaled(10f), y + scaled(50f), HudColors.text2.rgb, scaled(0.2f))
    }

    companion object {
        private val DROPS_TEXTURE: Identifier = Identifier("anpilotclient", "textures/icons/an2.png")
    }
}
