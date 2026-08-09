package anpilot.client.features.ai.utils.litematic

import anpilot.client.compat.LevelRenderContext
import anpilot.client.features.ai.utils.LitematicLoader

class LitematicSectionMeshCache {
    fun render(
        context: LevelRenderContext,
        cache: LitematicLoader.RenderCache,
        sections: List<LitematicLoader.RenderSection>,
        renderBuilt: Boolean
    ) {
        // 1.20.1 fallback: mesh cache rendering disabled
    }

    fun clear() {}
}
