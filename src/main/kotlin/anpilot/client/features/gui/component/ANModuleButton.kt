package anpilot.client.features.gui.component

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.gui.element.BindElement
import anpilot.client.features.gui.element.BooleanElement
import anpilot.client.features.gui.element.ColorPickerElement
import anpilot.client.features.gui.element.ConfigGroupElement
import anpilot.client.features.gui.element.FileSelectElement
import anpilot.client.features.gui.element.FriendGroupElement
import anpilot.client.features.gui.element.ItemSelectElement
import anpilot.client.features.gui.element.ModeElement
import anpilot.client.features.gui.element.SliderElement
import anpilot.client.features.gui.element.StringElement
import anpilot.client.features.gui.element.ThemeGroupElement
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.anpilot.ANPilotConfig
import anpilot.client.features.module.anpilot.ANPilotFriend
import anpilot.client.features.module.anpilot.ANPilotGuiEditor
import anpilot.client.features.module.anpilot.ANPilotTheme
import anpilot.client.features.module.anpilot.ANTheme
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.Bind
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.setting.impl.ConfigGroupSetting
import anpilot.client.features.setting.impl.FileSelectSetting
import anpilot.client.features.setting.impl.FriendGroupSetting
import anpilot.client.features.setting.impl.ItemSelectSetting
import anpilot.client.features.setting.impl.ThemeGroupSetting
import anpilot.client.features.utility.Animation
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

class ANModuleButton(
    private val module: ANBaseModule,
    private val onModulePrimaryClick: (ANBaseModule) -> Boolean = { false }
) : ANElement(height = 38f) {
    val searchableName: String
        get() = "${module.name} ${module.getDisplayHudName()}"

    var maxVisibleY: Float = Float.MAX_VALUE

    private var binding = false
    private val openAnimation = Animation(module.isOpen, 180f) { Animation.easeOutCubic(it) }

    private val elements = module.getSettings()
        .filterNot { it.name in INTERNAL_SETTINGS }
        .mapNotNull { setting -> createElement(setting)?.let { SettingElement(setting, it) } }
        .toMutableList()

    override fun render(context: ANGuiRenderContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        syncAnimations()
        if (y + BUTTON_HEIGHT <= maxVisibleY) {
            val hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + BUTTON_HEIGHT
            val fillColor = when {
                module.enabled -> ANTheme.BtnOnFill
                hovered -> ANTheme.BtnHoverFill
                else -> ANTheme.BtnFill
            }
            val borderColor = if (module.enabled) ANTheme.BtnOnBorder else ANTheme.BtnBorder
            val textColor = if (module.enabled) ANTheme.BtnText.rgb else ANTheme.BtnOffText.rgb
            context.borderedRoundedRect(x, y, width, BUTTON_HEIGHT, ANTheme.BtnRadius, ANTheme.BtnBorderWidth, fillColor, borderColor)
            val bindLabel = module.getBind().displayName
            val showBind = isLeftShiftDown() && !hasTextInputFocused()
            if (binding) {
                val pressingLabel = "Pressing"
                val pressingWidth = context.textWidth(pressingLabel).toFloat()
                context.text(pressingLabel, x + (width - pressingWidth) / 2, y + 5f, textColor)
            } else if (showBind) {
                val bindWidth = context.textWidth(bindLabel).toFloat()
                context.text(bindLabel, x + (width - bindWidth)/2, y + 5f, textColor)
            } else {
                context.text(module.getDisplayHudName(), x + 6f, y + 5f, textColor)
                val dotColor = if (module.enabled) ANTheme.BtnOnDot else ANTheme.BtnDot
                context.roundedRect(x + width - 12f, y + 6f, 6f, 6f, 3f, dotColor)
                if (hovered && module.description.isNotBlank()) {
                    ANTooltipManager.setTooltip(module.description, x, y, width)
                }
            }
        }

        val openFactor = openFactor()
        if (openFactor > 0.01f) {
            val lineTop = y + BUTTON_HEIGHT + SETTINGS_TOP_GAP
            var currentY = lineTop
            val clipHeight = settingsHeight() * openFactor
            context.pushScissor(x, lineTop-2f, width, (maxVisibleY - lineTop).coerceAtMost(clipHeight).coerceAtLeast(0f))
            try {
                visibleElements().forEach { settingElement ->
                    val element = settingElement.element
                    if (currentY >= maxVisibleY) return@forEach
                    val isGroupElement = element is ThemeGroupElement || element is FriendGroupElement || element is ConfigGroupElement
                    val shift = if (isGroupElement) 5f else 0f
                    element.x = x + SETTINGS_SIDE_PADDING - 2f - shift
                    element.y = currentY - (1f - openFactor) * 5f
                    element.width = width - SETTINGS_SIDE_PADDING + shift
                    element.render(context, mouseX, mouseY, deltaTicks)
                    currentY += element.height + SETTING_GAP
                }
            } finally {
                context.popScissor()
            }
            if (currentY > lineTop && shouldRenderSettingsLine()) {
                context.roundedRect(x+2 , lineTop, 2f, ((currentY - lineTop) * openFactor).coerceAtLeast(4f).coerceAtMost(maxVisibleY - lineTop), 1f, ANTheme.BtnOnDot)
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (binding) {
            module.setBind(button, true)
            binding = false
            return true
        }

        if (isHovered(mouseX, mouseY) && mouseY <= y + BUTTON_HEIGHT && y + BUTTON_HEIGHT <= maxVisibleY) {
            when (button) {
                0 -> if (!onModulePrimaryClick(module)) module.toggle()
                1 -> module.setOpen(!module.isOpen)
                2 -> binding = true
            }
            return true
        }

        if (module.isOpen) {
            visibleElements().forEach {
                val element = it.element
                if (element.y + element.height <= maxVisibleY && element.mouseClicked(mouseX, mouseY, button)) return true
            }
        }
        return false
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (module.isOpen) {
            visibleElements().forEach {
                val element = it.element
                if (element.y + element.height <= maxVisibleY && element.mouseReleased(mouseX, mouseY, button)) return true
            }
        }
        return false
    }

    override fun keyPressed(key: Int, scanCode: Int, modifiers: Int): Boolean {
        if (binding) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                module.setBind(-1, false)
            } else {
                module.setBind(key, false)
            }
            binding = false
            return true
        }

        if (module.isOpen) {
            visibleElements().forEach {
                val element = it.element
                if (element.y + element.height <= maxVisibleY && element.keyPressed(key, scanCode, modifiers)) return true
            }
        }
        return false
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (module.isOpen) {
            visibleElements().forEach {
                val element = it.element
                if (element.y + element.height <= maxVisibleY && element.charTyped(chr, modifiers)) return true
            }
        }
        return false
    }

    fun totalHeight(): Float {
        syncAnimations()
        return BUTTON_HEIGHT + settingsHeight() * openFactor()
    }

    private fun visibleElements(): List<SettingElement> = elements.filter { it.setting.isVisible() }

    private fun settingsHeight(): Float {
        val visible = visibleElements()
        if (visible.isEmpty()) return 0f
        return SETTINGS_TOP_GAP + visible.sumOf { (it.element.height + SETTING_GAP).toDouble() }.toFloat()
    }

    private fun syncAnimations() {
        if (openAnimation.state != module.isOpen) {
            openAnimation.state = module.isOpen
        }
    }

    private fun openFactor(): Float = if (animationsEnabled()) {
        openAnimation.getFactor().toFloat().coerceIn(0f, 1f)
    } else if (module.isOpen) {
        1f
    } else {
        0f
    }

    private fun animationsEnabled(): Boolean = ANPilotGuiEditor.animationsEnabled()

    private fun hasTextInputFocused(): Boolean = activeEditingElement != null

    private fun renderDescription(context: ANGuiRenderContext) {
        val descriptionWidth = context.textWidth(module.description, 0.62f).toFloat() + DESCRIPTION_PADDING * 2f
        val boxWidth = descriptionWidth.coerceAtMost(context.width - 10f)
        val boxX = (x + (width - boxWidth) / 2f).coerceIn(4f, context.width - boxWidth - 4f)
        val boxY = (y - 18f).coerceAtLeast(0f)
        context.borderedRoundedRect(boxX, boxY, boxWidth, 15f, 5f, 1f, ANTheme.DescFill, ANTheme.BtnBorder)
        context.text(module.description, boxX + DESCRIPTION_PADDING, boxY + 4f, ANTheme.DescText.rgb, 0.62f)
    }

    private fun isLeftShiftDown(): Boolean {
        val minecraft = Minecraft.getInstance()
        return InputConstants.isKeyDown(minecraft.window.window, InputConstants.KEY_LSHIFT)
    }

    private fun shouldRenderSettingsLine(): Boolean {
        return module !is ANPilotConfig && module !is ANPilotFriend && module !is ANPilotTheme
    }

    private data class SettingElement(val setting: ANSetting<*>, val element: ANElement)

    @Suppress("UNCHECKED_CAST")
    private fun createElement(setting: ANSetting<*>): ANElement? {
        val value = setting.value
        return when {
            value is Boolean -> BooleanElement(setting as ANSetting<Boolean>)
            value is Bind -> BindElement(setting as ANSetting<Bind>)
            value is ColorGroupSetting -> ColorPickerElement(setting as ANSetting<ColorGroupSetting>)
            value is ItemSelectSetting -> ItemSelectElement(setting as ANSetting<ItemSelectSetting>)
            value is ConfigGroupSetting -> ConfigGroupElement(setting as ANSetting<ConfigGroupSetting>)
            value is FileSelectSetting -> FileSelectElement(setting as ANSetting<FileSelectSetting>)
            value is ThemeGroupSetting -> ThemeGroupElement(setting as ANSetting<ThemeGroupSetting>)
            value is FriendGroupSetting -> FriendGroupElement(setting as ANSetting<FriendGroupSetting>)
            setting.isEnumSetting() -> ModeElement(setting as ANSetting<Enum<*>>)
            value is Number && setting.hasRestriction() -> SliderElement(setting as ANSetting<Number>)
            value is String -> StringElement(setting as ANSetting<String>)
            else -> null
        }
    }

    private companion object {
        private const val BUTTON_HEIGHT = 19f
        private const val SETTINGS_TOP_GAP = 4f
        private const val SETTING_GAP = 3f
        private const val SETTINGS_SIDE_PADDING = 10f
        private const val DESCRIPTION_PADDING = 5f
        private val INTERNAL_SETTINGS = setOf("Keybind", "IsOpen", "ItemSelectOpen", "Enabled")
    }
}

