package anpilot.client.minecraft.gui

import anpilot.client.bootstrap.ANServiceRegistry
import com.mojang.blaze3d.platform.InputConstants
import anpilot.client.compat.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import anpilot.client.features.gui.component.activeEditingElement
import anpilot.client.features.manager.ANConfigManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener

class ANClickGuiScreen : Screen(Component.literal("ANPilot ClickGui")) {
    private val dummyEditBox by lazy {
        EditBox(
            Minecraft.getInstance().font,
            0, 0, 0, 0,
            Component.literal("Dummy")
        ).apply {
            setFocused(true)
        }
    }

    override fun getFocused(): GuiEventListener? {
        if (activeEditingElement != null) {
            return dummyEditBox
        }
        return super.getFocused()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        ANServiceRegistry.runtime.clickGui.render(
            MinecraftGuiRenderContext(graphics, font, width, height),
            mouseX,
            mouseY,
            partialTick
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        return ANServiceRegistry.runtime.clickGui.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        return ANServiceRegistry.runtime.clickGui.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        return ANServiceRegistry.runtime.clickGui.mouseScrolled(mouseX, mouseY, amount) || super.mouseScrolled(mouseX, mouseY, amount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        return ANServiceRegistry.runtime.clickGui.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        return ANServiceRegistry.runtime.clickGui.charTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers)
    }

    override fun renderBackground(graphics: GuiGraphics) {
        if (Minecraft.getInstance().level == null) {
            super.renderBackground(graphics)
        }
    }

    override fun removed() {
        super.removed()
        activeEditingElement = null
        ANConfigManager.saveCurrent()
    }

    override fun isPauseScreen(): Boolean = false
}
