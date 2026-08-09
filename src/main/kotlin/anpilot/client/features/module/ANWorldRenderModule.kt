package anpilot.client.features.module

import anpilot.client.compat.LevelRenderContext

interface ANWorldRenderModule {
    fun renderWorld(context: LevelRenderContext)
}
