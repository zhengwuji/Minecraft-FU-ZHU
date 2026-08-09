package anpilot.client.features.event.impl

import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.minecraft.gui.MinecraftGuiRenderContext
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import anpilot.client.compat.GuiGraphicsExtractor

object ANMinecraftEvents {
    fun tick() {
        if (!ANServiceRegistry.isInitialized) return
        ANServiceRegistry.runtime.eventBus.post(ANTickEvent())
        ANServiceRegistry.runtime.moduleManager.tick()
    }

    fun renderWorld(context: LevelRenderContext) {
        if (!ANServiceRegistry.isInitialized) return
        ANServiceRegistry.runtime.eventBus.post(Render3DEvent(context, 0f))
        ANServiceRegistry.runtime.moduleManager.renderWorld(context)
    }

    fun renderHud(context: GuiGraphicsExtractor, delta: Float) {
        if (!ANServiceRegistry.isInitialized) return
        val mc = Minecraft.getInstance()
        val window = mc.window
        val font = mc.font
        val renderContext = MinecraftGuiRenderContext(context, font, window.guiScaledWidth, window.guiScaledHeight)
        ANServiceRegistry.runtime.moduleManager.renderHud(renderContext)
        ANServiceRegistry.runtime.eventBus.post(Render2DEvent(context, delta))
    }
}
