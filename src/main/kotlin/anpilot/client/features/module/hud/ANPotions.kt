package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffectCategory
import java.awt.Color

class ANPotions : ANDraggableHudModule("Potions", "当前玩家生效的药水效果及剩余时间", "药水效果", 20f, 430f) {
    val beneficialColor = addSetting(ANSetting("Beneficial_Color", ColorGroupSetting(Color(0xFFF6B207.toInt(), true).rgb)))
    val harmfulColor = addSetting(ANSetting("Harmful_Color", ColorGroupSetting(Color(0xAB632BF8.toInt(), true).rgb)))

    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        val effects = Minecraft.getInstance().player?.activeEffects?.toList().orEmpty()
        if (effects.isEmpty()) {
            if (editor) {
                setHudBounds(scaled(120f), scaled(40f))
                context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(8f), scaled(1.5f), HudColors.panelFillColor, HudColors.panelBorderColor)
                context.text("Potion", x + scaled(10f), y + scaled(10f), HudColors.text1.rgb, hudScale)
            } else {
                setHudBounds(0f, 0f)
            }
            return
        }
        val lines = effects.map { effect ->
            val duration = effect.duration / 20
            val time = "%d:%02d".format(duration / 60, duration % 60)
            val text = effect.effect.displayName.string + (effect.amplifier + 1) + "  " + time
            val fill = when (effect.effect.category) {
                MobEffectCategory.BENEFICIAL -> Color(beneficialColor.value.getColor(), true)
                MobEffectCategory.HARMFUL -> Color(harmfulColor.value.getColor(), true)
                else -> HudColors.panelFillColor
            }
            text to fill
        }
        val maxWidth = lines.maxOfOrNull { context.textWidth(it.first, hudScale).toFloat() + scaled(20f) } ?: scaled(120f)
        setHudBounds(maxWidth, (lines.size * scaled(45f)).coerceAtLeast(scaled(40f)))
        if (lines.isEmpty() && editor) {
            context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(8f), scaled(1.5f), HudColors.panelFillColor, HudColors.panelBorderColor)
            context.text("Potion", x + scaled(10f), y + scaled(10f), HudColors.text1.rgb, hudScale)
            return
        }
        lines.forEachIndexed { index, pair ->
            val yPos = y + index * scaled(22f)
            context.borderedRoundedRect(x, yPos, context.textWidth(pair.first, hudScale).toFloat() + scaled(20f), scaled(20f), scaled(8f), scaled(1.5f), pair.second, HudColors.panelBorderColor)
            context.text(pair.first, x + scaled(10f), yPos + scaled(5f), HudColors.text1.rgb, hudScale)
        }
    }
}
