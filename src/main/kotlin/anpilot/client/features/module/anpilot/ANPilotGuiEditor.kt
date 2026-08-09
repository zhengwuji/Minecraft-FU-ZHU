package anpilot.client.features.module.anpilot

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.manager.ANConfigManager
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.misc.ANBotTask.TriggerMode
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.features.setting.impl.FileSelectSetting
import com.mojang.blaze3d.platform.InputConstants
import java.awt.Color

class ANPilotGuiEditor : ANBaseModule(
    name = "ClickGui",
    description = "Client GUI editor entry.",
    category = ANModuleCategory.CLIENT,
    chineseName = "界面编辑器"
) {
    enum class Language {
        English,
        Chinese
    }

    enum class Group {
        FILL,
        BORDER,
        RADIUS,
        DECOR
    }

    val language = addSetting(ANSetting("Language", Language.Chinese))
    val animations = addSetting(ANSetting("Animations", false))
    val groupSelect = addSetting(ANSetting("Pages", Group.FILL))

    val bgTint = addSetting(ANSetting("BgTint", ColorGroupSetting(Color(0xFFFFFFFF.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val panelFill = addSetting(ANSetting("PanelFill", ColorGroupSetting(Color(0xD93B1432.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val panelBorder = addSetting(ANSetting("PanelBorder", ColorGroupSetting(Color(0xD9FF8CC8.toInt(), true).rgb)) { groupSelect.value == Group.BORDER })
    val panelText = addSetting(ANSetting("PanelText", ColorGroupSetting(Color(0xFFFFEAF6.toInt(), true).rgb)) { groupSelect.value == Group.FILL })

    val btnFill = addSetting(ANSetting("BtnFill", ColorGroupSetting(Color(0xCC4A183C.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val btnBorder = addSetting(ANSetting("BtnBorder", ColorGroupSetting(Color(0xD9FF8CC8.toInt(), true).rgb)) { groupSelect.value == Group.BORDER })
    val btnHoverFill = addSetting(ANSetting("BtnHoverFill", ColorGroupSetting(Color(0xE0722A5B.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val btnOnFill = addSetting(ANSetting("BtnOnFill", ColorGroupSetting(Color(0xE01D4ED8.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val btnOnBorder = addSetting(ANSetting("BtnOnBorder", ColorGroupSetting(Color(0xFF60A5FA.toInt(), true).rgb)) { groupSelect.value == Group.BORDER })
    val btnText = addSetting(ANSetting("BtnText", ColorGroupSetting(Color(0xFFFFEEF7.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val btnOffText = addSetting(ANSetting("BtnOffText", ColorGroupSetting(Color(0xC7FFEAF6.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val btnDot = addSetting(ANSetting("BtnDot", ColorGroupSetting(Color(0xFFFF8CC8.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val btnOnDot = addSetting(ANSetting("BtnOnDot", ColorGroupSetting(Color(0xFF93C5FD.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val descFill = addSetting(ANSetting("DescFill", ColorGroupSetting(Color(0xE6260B20.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val descText = addSetting(ANSetting("DescText", ColorGroupSetting(Color(0xFFFFEAF6.toInt(), true).rgb)) { groupSelect.value == Group.FILL })

    val setText = addSetting(ANSetting("SetText", ColorGroupSetting(Color(0xFFFFEAF6.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val setCtrlFill = addSetting(ANSetting("SetCtrlFill", ColorGroupSetting(Color(0xCC3A1230.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val setCtrlBorder = addSetting(ANSetting("SetCtrlBorder", ColorGroupSetting(Color(0xD9FF8CC8.toInt(), true).rgb)) { groupSelect.value == Group.BORDER })
    val setAccent = addSetting(ANSetting("SetAccent", ColorGroupSetting(Color(0xFF60A5FA.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val setMutedText = addSetting(ANSetting("SetMutedText", ColorGroupSetting(Color(0xA6FFEAF6.toInt(), true).rgb)) { groupSelect.value == Group.FILL })

    val selFill = addSetting(ANSetting("SelFill", ColorGroupSetting(Color(0xCC3A1230.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val selBorder = addSetting(ANSetting("SelBorder", ColorGroupSetting(Color(0xD9FF8CC8.toInt(), true).rgb)) { groupSelect.value == Group.BORDER })
    val selHoverFill = addSetting(ANSetting("SelHoverFill", ColorGroupSetting(Color(0xE0722A5B.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val selOnFill = addSetting(ANSetting("SelOnFill", ColorGroupSetting(Color(0xE01D4ED8.toInt(), true).rgb)) { groupSelect.value == Group.FILL })
    val selOnBorder = addSetting(ANSetting("SelOnBorder", ColorGroupSetting(Color(0xFF60A5FA.toInt(), true).rgb)) { groupSelect.value == Group.BORDER })

    val panelRadius = addSetting(ANSetting("PanelRadius", 13f, 1f, 20f) { groupSelect.value == Group.RADIUS })
    val btnRadius = addSetting(ANSetting("BtnRadius", 8f, 1f, 10f) { groupSelect.value == Group.RADIUS })
    val bgRadius = addSetting(ANSetting("BgRadius", 18f, 0f, 48f) { groupSelect.value == Group.RADIUS })
    val panelBorderWidth = addSetting(ANSetting("PanelBorderW", 1.0f, 0.0f, 4.0f) { groupSelect.value == Group.RADIUS })
    val btnBorderWidth = addSetting(ANSetting("BtnBorderW", 1f, 0.0f, 2.0f) { groupSelect.value == Group.RADIUS })

    val decorEnabled = addSetting(ANSetting("Decor", true) { groupSelect.value == Group.DECOR })
    val decorFile = addSetting(ANSetting("DecorFile", FileSelectSetting(ANConfigManager::customDecorFileNames)) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorColor = addSetting(ANSetting("DecorColor", ColorGroupSetting(Color(0xFFFFEEF7.toInt(), true).rgb)) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorDensity = addSetting(ANSetting("DecorDensity", 0.22f, 0.0f, 1.5f) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorSize = addSetting(ANSetting("DecorSize", 13f, 4f, 32f) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorMinScale = addSetting(ANSetting("DecorMinScale", 0.65f, 0.2f, 2.0f) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorMaxScale = addSetting(ANSetting("DecorMaxScale", 1.25f, 0.2f, 3.0f) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorRotation = addSetting(ANSetting("DecorRotation", 55f, 0f, 180f) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorOffset = addSetting(ANSetting("DecorOffset", -1.5f, -24f, 24f) { groupSelect.value == Group.DECOR && decorEnabled.value })
    val decorSeed = addSetting(ANSetting("DecorSeed", 13579, 0, 999999) { groupSelect.value == Group.DECOR && decorEnabled.value })

    init {
        setBind(InputConstants.KEY_F4, false)
        activeLanguageSetting = language
        activeAnimationsSetting = animations
        syncToTheme()
    }

    override fun isToggleable(): Boolean = false

    fun ensureDefaultBind() {
        if (getBind().key == -1) {
            setBind(InputConstants.KEY_F4, false)
        }
    }

    fun syncToTheme() {
        ensureDefaultBind()
        getSettings().forEach { setting ->
            when (val v = setting.value) {
                is ColorGroupSetting -> updateTheme(setting)
                is FileSelectSetting -> ANTheme.updateStringFromSetting(setting.name, v.currentFileName())
                is Boolean -> ANTheme.updateBooleanFromSetting(setting.name, v)
                is Int -> ANTheme.updateFloatFromSetting(setting.name, v.toFloat())
                is Float -> ANTheme.updateFloatFromSetting(setting.name, v)
                is Double -> ANTheme.updateFloatFromSetting(setting.name, v.toFloat())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateTheme(setting: ANSetting<*>) {
        ANTheme.updateFromSetting(setting.name, (setting as ANSetting<ColorGroupSetting>).value.getColor())
    }

    companion object {
        private var activeLanguageSetting: ANSetting<Language>? = null
        private var activeAnimationsSetting: ANSetting<Boolean>? = null

        fun useChineseNames(): Boolean = activeLanguageSetting?.value == Language.Chinese

        fun animationsEnabled(): Boolean = activeAnimationsSetting?.value != false
    }
}
