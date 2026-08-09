package anpilot.client.compat

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

typealias GuiGraphicsExtractor = GuiGraphics
typealias Identifier = ResourceLocation

data class PlayerSkin(val texture: ResourceLocation)

class CameraRenderState(val pos: Vec3)
class LevelState(val cameraRenderState: CameraRenderState)

class NodeCollector(val bufferSource: MultiBufferSource) {
    fun submitCustomGeometry(
        poseStack: PoseStack,
        renderType: RenderType,
        consumerAction: (Any?, VertexConsumer) -> Unit
    ) {
        val consumer = bufferSource.getBuffer(renderType)
        consumerAction(null, consumer)
    }
}

class LevelRenderContext(
    val poseStack: PoseStack,
    val bufferSource: MultiBufferSource,
    val cameraPos: Vec3,
    val partialTick: Float
) {
    fun poseStack(): PoseStack = poseStack
    fun levelState(): LevelState = LevelState(CameraRenderState(cameraPos))
    fun submitNodeCollector(): NodeCollector = NodeCollector(bufferSource)
}
