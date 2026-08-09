package anpilot.client.minecraft.gui

import anpilot.client.bootstrap.ANServiceRegistry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import anpilot.client.features.manager.ANConfigManager
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphics

class ANHudEditorScreen : Screen(Component.literal("ANPilot HUD Editor")) {
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val gui = MinecraftGuiRenderContext(graphics, font, width, height)
        ANServiceRegistry.runtime.moduleManager.renderHud(gui, editor = true)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun renderBackground(graphics: GuiGraphics) {
    }

    override fun removed() {
        super.removed()
        ANConfigManager.saveCurrent()
    }

    override fun isPauseScreen(): Boolean = false
}
