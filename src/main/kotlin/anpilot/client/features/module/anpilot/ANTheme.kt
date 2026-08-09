package anpilot.client.features.module.anpilot

import java.awt.Color

object ANTheme {
    const val MODULE_RADIUS = 15f
    const val MODULE_BORDER = 3f
    const val BUTTON_RADIUS = 10f
    const val BUTTON_BORDER = 1.5f
    const val BUTTON_OPT_RADIUS = 6f
    const val BUTTON_COLOR_RADIUS = 8f
    const val BUTTON_OPT_BORDER = 1.5f

    val Purple: Color = Color(90, 32, 246, 249)
    val Transparent: Color = Color(255, 255, 255, 0)
    val White: Color = Color(255, 255, 255, 255)
    val Black: Color = Color(0, 0, 0, 255)
    val Red: Color = Color(255, 0, 0, 255)
    val Green: Color = Color(0, 255, 0, 255)
    val Blue: Color = Color(0, 0, 255, 255)
    val Yellow: Color = Color(255, 255, 0, 255)
    val Cyan: Color = Color(0, 255, 255, 255)
    val Magenta: Color = Color(255, 0, 255, 255)
    val Gray: Color = Color(44, 44, 44, 255)

    var BgTint: Color = White
        private set
    var PanelFill: Color = Color(0xD93B1432.toInt(), true)
        private set
    var PanelBorder: Color = Color(0xD9FF8CC8.toInt(), true)
        private set
    var PanelText: Color = Color(0xFFFFFFFF.toInt(), true)
        private set

    var BtnFill: Color = Color(0xCC4A183C.toInt(), true)
        private set
    var BtnBorder: Color = Color(0xD9FF8CC8.toInt(), true)
        private set
    var BtnHoverFill: Color = Color(0xE0722A5B.toInt(), true)
        private set
    var BtnOnFill: Color = Color(0xE01D4ED8.toInt(), true)
        private set
    var BtnOnBorder: Color = Color(0xFF60A5FA.toInt(), true)
        private set
    var BtnText: Color = Color(0xFFFFFFFF.toInt(), true)
        private set
    var BtnOffText: Color = Color(0xFFF1F5F9.toInt(), true)
        private set
    var BtnDot: Color = Color(0xFFFF8CC8.toInt(), true)
        private set
    var BtnOnDot: Color = Color(0xFF93C5FD.toInt(), true)
        private set
    var DescFill: Color = Color(0xF50F172A.toInt(), true)
        private set
    var DescText: Color = Color(0xFFFFFFFF.toInt(), true)
        private set

    var SetText: Color = Color(0xFFFFFFFF.toInt(), true)
        private set
    var SetCtrlFill: Color = Color(0xCC3A1230.toInt(), true)
        private set
    var SetCtrlBorder: Color = Color(0xD9FF8CC8.toInt(), true)
        private set
    var SetAccent: Color = Color(0xFF60A5FA.toInt(), true)
        private set
    var SetMutedText: Color = Color(0xFFE2E8F0.toInt(), true)
        private set

    var SelFill: Color = Color(0xCC3A1230.toInt(), true)
        private set
    var SelBorder: Color = Color(0xD9FF8CC8.toInt(), true)
        private set
    var SelHoverFill: Color = Color(0xE0722A5B.toInt(), true)
        private set
    var SelOnFill: Color = Color(0xE01D4ED8.toInt(), true)
        private set
    var SelOnBorder: Color = Color(0xFF60A5FA.toInt(), true)
        private set

    var PanelRadius: Float = 15f
        private set
    var BtnRadius: Float = 10f
        private set
    var BgRadius: Float = 18f
        private set

    var PanelBorderWidth: Float = 3f
        private set
    var BtnBorderWidth: Float = 1.5f
        private set

    var DecorEnabled: Boolean = true
        private set
    var DecorFile: String = ""
        private set
    var DecorColor: Color = Color(0xFFFFEEF7.toInt(), true)
        private set
    var DecorDensity: Float = 0.22f
        private set
    var DecorSize: Float = 13f
        private set
    var DecorMinScale: Float = 0.65f
        private set
    var DecorMaxScale: Float = 1.25f
        private set
    var DecorRotation: Float = 55f
        private set
    var DecorOffset: Float = -1.5f
        private set
    var DecorSeed: Long = 13579L
        private set

    private val colorMap = mutableMapOf<String, Int>()
    private val floatMap = mutableMapOf<String, Float>()
    private val booleanMap = mutableMapOf<String, Boolean>()
    private val stringMap = mutableMapOf<String, String>()

    fun updateFromSetting(themeKey: String?, colorValue: Int) {
        if (themeKey == null) return
        colorMap[themeKey] = colorValue
        updateAllReferences()
    }

    fun updateFloatFromSetting(themeKey: String?, floatValue: Float) {
        if (themeKey == null) return
        floatMap[themeKey] = floatValue
        updateAllReferences()
    }

    fun updateBooleanFromSetting(themeKey: String?, booleanValue: Boolean) {
        if (themeKey == null) return
        booleanMap[themeKey] = booleanValue
        updateAllReferences()
    }

    fun updateStringFromSetting(themeKey: String?, stringValue: String) {
        if (themeKey == null) return
        stringMap[themeKey] = stringValue
        updateAllReferences()
    }

    fun getColor(themeKey: String): Color = Color(colorMap[themeKey] ?: defaultColor(themeKey).rgb, true)

    private fun updateAllReferences() {
        PanelRadius = floatMap["PanelRadius"] ?: floatMap["panelRadius"] ?: 15f
        BtnRadius = floatMap["BtnRadius"] ?: floatMap["btnRadius"] ?: 10f
        BgRadius = floatMap["BgRadius"] ?: 18f
        PanelBorderWidth = floatMap["PanelBorderW"] ?: floatMap["panelBorderW"] ?: 3f
        BtnBorderWidth = floatMap["BtnBorderW"] ?: floatMap["btnBorderW"] ?: 1.5f
        DecorEnabled = booleanMap["Decor"] ?: true
        DecorFile = stringMap["DecorFile"].orEmpty()
        DecorDensity = floatMap["DecorDensity"] ?: 0.22f
        DecorSize = floatMap["DecorSize"] ?: 13f
        DecorMinScale = floatMap["DecorMinScale"] ?: 0.65f
        DecorMaxScale = floatMap["DecorMaxScale"] ?: 1.25f
        DecorRotation = floatMap["DecorRotation"] ?: 55f
        DecorOffset = floatMap["DecorOffset"] ?: -1.5f
        DecorSeed = (floatMap["DecorSeed"] ?: 13579f).toLong()

        BgTint = getColor("BgTint")
        PanelFill = getColor("PanelFill")
        PanelBorder = getColor("PanelBorder")
        PanelText = getColor("PanelText")
        DecorColor = getColor("DecorColor")

        BtnFill = getColor("BtnFill")
        BtnBorder = getColor("BtnBorder")
        BtnHoverFill = getColor("BtnHoverFill")
        BtnOnFill = getColor("BtnOnFill")
        BtnOnBorder = getColor("BtnOnBorder")
        BtnText = getColor("BtnText")
        BtnOffText = getColor("BtnOffText")
        BtnDot = getColor("BtnDot")
        BtnOnDot = getColor("BtnOnDot")
        DescFill = getColor("DescFill")
        DescText = getColor("DescText")

        SetText = getColor("SetText")
        SetCtrlFill = getColor("SetCtrlFill")
        SetCtrlBorder = getColor("SetCtrlBorder")
        SetAccent = getColor("SetAccent")
        SetMutedText = getColor("SetMutedText")

        SelFill = getColor("SelFill")
        SelBorder = getColor("SelBorder")
        SelHoverFill = getColor("SelHoverFill")
        SelOnFill = getColor("SelOnFill")
        SelOnBorder = getColor("SelOnBorder")
    }

    private fun defaultColor(themeKey: String): Color = when (themeKey) {
        "BgTint" -> White
        "PanelFill" -> Color(0xD93B1432.toInt(), true)
        "PanelBorder" -> Color(0xD9FF8CC8.toInt(), true)
        "PanelText" -> Color(0xFFFFFFFF.toInt(), true)
        "BtnFill" -> Color(0xCC4A183C.toInt(), true)
        "BtnBorder" -> Color(0xD9FF8CC8.toInt(), true)
        "BtnHoverFill" -> Color(0xE0722A5B.toInt(), true)
        "BtnOnFill" -> Color(0xE01D4ED8.toInt(), true)
        "BtnOnBorder" -> Color(0xFF60A5FA.toInt(), true)
        "BtnText" -> Color(0xFFFFFFFF.toInt(), true)
        "BtnOffText" -> Color(0xFFF1F5F9.toInt(), true)
        "BtnDot" -> Color(0xFFFF8CC8.toInt(), true)
        "BtnOnDot" -> Color(0xFF93C5FD.toInt(), true)
        "DescFill" -> Color(0xF50F172A.toInt(), true)
        "DescText" -> Color(0xFFFFFFFF.toInt(), true)
        "SetText" -> Color(0xFFFFFFFF.toInt(), true)
        "SetCtrlFill" -> Color(0xCC3A1230.toInt(), true)
        "SetCtrlBorder" -> Color(0xD9FF8CC8.toInt(), true)
        "SetAccent" -> Color(0xFF60A5FA.toInt(), true)
        "SetMutedText" -> Color(0xFFE2E8F0.toInt(), true)
        "SelFill" -> Color(0xCC3A1230.toInt(), true)
        "SelBorder" -> Color(0xD9FF8CC8.toInt(), true)
        "SelHoverFill" -> Color(0xE0722A5B.toInt(), true)
        "SelOnFill" -> Color(0xE01D4ED8.toInt(), true)
        "SelOnBorder" -> Color(0xFF60A5FA.toInt(), true)
        "DecorColor" -> Color(0xFFFFEEF7.toInt(), true)
        else -> White
    }
}
