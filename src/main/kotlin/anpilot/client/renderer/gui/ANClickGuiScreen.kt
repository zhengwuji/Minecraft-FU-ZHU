package anpilot.client.renderer.gui

import anpilot.client.renderer.ANGUIRenderer
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

class ANClickGuiScreen : Screen(Component.literal("ANPilot ClickGui")) {
    private val categories = listOf(
        CategoryPreview(
            "Combat",
            "Targeting and survival tools",
            listOf(
                ModulePreview("杀戮光环", "自动选择并攻击目标", true),
                ModulePreview("水晶光环", "末地水晶战斗辅助", false),
                ModulePreview("自动图腾", "危险时快速换图腾", true),
                ModulePreview("自动补刀", "低血量目标收割", false)
            )
        ),
        CategoryPreview(
            "Render",
            "World and entity visuals",
            listOf(
                ModulePreview("方块高亮", "显示选中方块轮廓", true),
                ModulePreview("实体 ESP", "极简实体标记", true),
                ModulePreview("存储箱透视", "显示容器位置", false),
                ModulePreview("轨迹线", "预测投掷物轨迹", true)
            )
        ),
        CategoryPreview(
            "Movement",
            "Motion and terrain control",
            listOf(
                ModulePreview("疾跑", "自动保持冲刺", true),
                ModulePreview("飞行", "创造式移动预览", false),
                ModulePreview("长跳", "增强水平位移", false),
                ModulePreview("无摔落", "降低落地风险", true)
            )
        ),
        CategoryPreview(
            "Player",
            "Inventory and player helpers",
            listOf(
                ModulePreview("自动工具", "选择最高效工具", true),
                ModulePreview("自动进食", "低饱食度补充", false),
                ModulePreview("背包整理", "快速整理物品栏", false),
                ModulePreview("自动修补", "经验修复装备", true)
            )
        )
    )

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val panelWidth = 690f
        val panelHeight = 430f
        val panelX = (width - panelWidth) / 2f
        val panelY = (height - panelHeight) / 2f
        val contentX = panelX + 30f
        val contentY = panelY + 78f

        ANGUIRenderer.rect(graphics, 0f, 0f, width.toFloat(), height.toFloat(), Color(0xAA111722.toInt(), true))
        drawNeumorphicPanel(graphics, panelX, panelY, panelWidth, panelHeight, 26f, Color(0xEE161D29.toInt(), true))

        graphics.drawString(font, "ANPilot", (panelX + 34f).toInt(), (panelY + 28f).toInt(), 0xF2FFFFFF.toInt(), false)
        graphics.drawString(font, "neumorphic clickgui preview", (panelX + 34f).toInt(), (panelY + 44f).toInt(), 0x7AFFFFFF, false)

        drawInsetPanel(graphics, panelX + panelWidth - 185f, panelY + 25f, 150f, 30f, 14f)
        graphics.drawString(font, "Search modules", (panelX + panelWidth - 168f).toInt(), (panelY + 35f).toInt(), 0x73FFFFFF, false)

        val gap = 18f
        val categoryWidth = (panelWidth - 60f - gap) / 2f
        val categoryHeight = 145f
        categories.forEachIndexed { index, category ->
            val column = index % 2
            val row = index / 2
            drawCategoryPanel(
                graphics,
                contentX + column * (categoryWidth + gap),
                contentY + row * (categoryHeight + gap),
                categoryWidth,
                categoryHeight,
                category
            )
        }

        graphics.drawCenteredString(font, "Right Shift / Esc", width / 2, (panelY + panelHeight + 14f).toInt(), 0x66FFFFFF)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawCategoryPanel(graphics: GuiGraphics, x: Float, y: Float, width: Float, height: Float, category: CategoryPreview) {
        drawNeumorphicPanel(graphics, x, y, width, height, 20f, Color(0xFF171F2B.toInt(), true))
        graphics.drawString(font, category.name, (x + 18f).toInt(), (y + 16f).toInt(), 0xF2FFFFFF.toInt(), false)
        graphics.drawString(font, category.subtitle, (x + 18f).toInt(), (y + 31f).toInt(), 0x73FFFFFF, false)

        val pillWidth = (width - 46f) / 2f
        val pillHeight = 34f
        category.modules.forEachIndexed { index, module ->
            val column = index % 2
            val row = index / 2
            drawModulePill(
                graphics,
                x + 18f + column * (pillWidth + 10f),
                y + 58f + row * (pillHeight + 10f),
                pillWidth,
                pillHeight,
                module
            )
        }
    }

    private fun drawModulePill(graphics: GuiGraphics, x: Float, y: Float, width: Float, height: Float, module: ModulePreview) {
        if (module.enabled) {
            drawNeumorphicPanel(graphics, x, y, width, height, 13f, Color(0xFF1A2332.toInt(), true))
        } else {
            drawInsetPanel(graphics, x, y, width, height, 13f)
        }

        graphics.drawString(font, module.name, (x + 12f).toInt(), (y + 8f).toInt(), if (module.enabled) 0xE6FFFFFF.toInt() else 0xA6FFFFFF.toInt(), false)
        val dotColor = if (module.enabled) Color(0xFF7282FF.toInt(), true) else Color(0x40FFFFFF, true)
        ANGUIRenderer.roundedRect(graphics, x + width - 18f, y + 12f, 8f, 8f, 4f, dotColor)
    }

    private fun drawNeumorphicPanel(graphics: GuiGraphics, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) {
        for (i in 4 downTo 1) {
            ANGUIRenderer.roundedRect(graphics, x + i, y + i, width, height, radius, Color(0x09000000, true))
        }
        for (i in 3 downTo 1) {
            ANGUIRenderer.roundedRect(graphics, x - i, y - i, width, height, radius, Color(0x08FFFFFF, true))
        }
        ANGUIRenderer.roundedRect(graphics, x, y, width, height, radius, color)
    }

    private fun drawInsetPanel(graphics: GuiGraphics, x: Float, y: Float, width: Float, height: Float, radius: Float) {
        ANGUIRenderer.roundedRect(graphics, x, y, width, height, radius, Color(0xFF121925.toInt(), true))
        ANGUIRenderer.roundedRect(graphics, x + 1f, y + 1f, width - 2f, height - 2f, radius - 1f, Color(0x33000000, true))
        ANGUIRenderer.roundedRect(graphics, x + 2f, y + 2f, width - 4f, height - 4f, radius - 2f, Color(0x0FFFFFFF, true))
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == InputConstants.KEY_RSHIFT || keyCode == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }

        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false

    private data class CategoryPreview(val name: String, val subtitle: String, val modules: List<ModulePreview>)

    private data class ModulePreview(val name: String, val description: String, val enabled: Boolean)
}
