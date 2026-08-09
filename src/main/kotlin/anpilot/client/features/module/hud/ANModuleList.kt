package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.anpilot.ANPilotGuiEditor
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import java.awt.Color
import anpilot.client.bootstrap.ANServiceRegistry

class ANModuleList : ANDraggableHudModule("ModuleList", "已开启的功能模块列表(ArrayList)", "模块列表", 20f, 330f) {
    val onlyBind = addSetting(ANSetting("onlyBind", true))
    val bindColor = addSetting(ANSetting("BindColor", ColorGroupSetting(Color(0xFF35FA1F.toInt(), true).rgb)))

    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        val modules = ANServiceRegistry.runtime.moduleManager.allModules()
            .filterIsInstance<ANBaseModule>()
            .filter { module -> if (onlyBind.value) module.getBind().key != -1 else module.enabled }
            .sortedBy { context.textWidth(labelFor(it), hudScale) }
        val lines = modules.map { it to labelFor(it) }
        val maxWidth = lines.maxOfOrNull { context.textWidth(it.second, hudScale).toFloat() }?.plus(scaled(10f)) ?: scaled(20f)
        val height = (lines.size * scaled(14f)).coerceAtLeast(scaled(20f))
        setHudBounds(maxWidth, height)
        context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(10f), scaled(1f), HudColors.panelFillColor, HudColors.panelBorderColor)
        lines.forEachIndexed { index, pair ->
            val module = pair.first
            val text = pair.second
            val color = if (onlyBind.value && module.enabled) bindColor.value.getColor() else HudColors.text2.rgb
            context.text(text, x + scaled(5f), y + scaled(5f) + index * scaled(12f), color, hudScale)
        }
    }

    private fun labelFor(module: ANBaseModule): String {
        return if (onlyBind.value && module.name != "GuiEditor" && module.getBind().key != -1) {
            module.getDisplayName() + " [" + module.getBind().key.toChar() + "]"
        } else module.getDisplayName()
    }
}
