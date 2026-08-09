package anpilot.client.features.event.impl

import anpilot.client.compat.LevelRenderContext
import anpilot.client.compat.GuiGraphicsExtractor

class Render2DEvent(val context: GuiGraphicsExtractor, val tickDelta: Float)
class Render3DEvent(val context: LevelRenderContext, val tickDelta: Float)


