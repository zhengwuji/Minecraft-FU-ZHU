package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

class ANPopChams : ANBaseModule(
    name = "PopChams",
    description = "玩家爆图腾时在其位置生成灵魂残影特效",
    category = ANModuleCategory.RENDER,
    chineseName = "图腾Chams"
), ANWorldRenderModule {
    enum class Mode {
        Simple, Textured
    }

    val mode = addSetting(ANSetting("Mode", Mode.Textured))
    val secondLayer = addSetting(ANSetting("SecondLayer", true))
    val color = addSetting(ANSetting("Color", ColorGroupSetting(Color(0x8800FF00.toInt(), true).rgb)))
    val ySpeed = addSetting(ANSetting("YSpeed", 0, -10, 10))
    val aSpeed = addSetting(ANSetting("AlphaSpeed", 5, 1, 100))
    val rotSpeed = addSetting(ANSetting("RotationSpeed", 0.25f, 0f, 6f))

    private val popList = CopyOnWriteArrayList<Person>()

    override fun onDisable() {
        popList.clear()
    }

    override fun onTick() {
        popList.forEach { it.update(popList, aSpeed.value) }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val packet = event.packet as? ClientboundEntityEventPacket ?: return
        val level = mc.level ?: return
        val localPlayer = mc.player ?: return
        val entity = packet.getEntity(level) as? Player ?: return

        if (packet.eventId == 35.toByte()) {
            if (entity === localPlayer) return

            mc.execute {
                val skin = DefaultPlayerSkin.getDefaultSkin(entity.uuid)
                val bodyYaw = entity.yBodyRot
                val fakePlayer = object : Player(level, entity.blockPosition(), entity.yRot, GameProfile(entity.uuid, entity.name.string)) {
                    override fun isSpectator(): Boolean = false
                    override fun isCreative(): Boolean = false
                }

                fakePlayer.setPos(entity.x, entity.y, entity.z)
                fakePlayer.xRot = entity.xRot
                fakePlayer.yRot = entity.yRot
                fakePlayer.yBodyRot = entity.yBodyRot
                fakePlayer.yHeadRot = entity.yHeadRot

                popList.add(Person(fakePlayer, skin, color.value.getColorRGB().alpha))
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (popList.isEmpty()) return

        val partialTick = mc.frameTime
        val poseStack = context.poseStack()
        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()

        popList.forEach { person ->
            renderEntity(poseStack, bufferSource, person.player, person.modelPlayer, person.texture, person.getAlphaClamped(), partialTick)
        }
    }

    private fun renderEntity(
        matrices: PoseStack,
        bufferSource: MultiBufferSource,
        entity: Player,
        modelBase: PlayerModel<Player>,
        texture: ResourceLocation,
        alpha: Int,
        partialTick: Float
    ) {
        modelBase.leftPants.visible = secondLayer.value
        modelBase.rightPants.visible = secondLayer.value
        modelBase.leftSleeve.visible = secondLayer.value
        modelBase.rightSleeve.visible = secondLayer.value
        modelBase.jacket.visible = secondLayer.value
        modelBase.hat.visible = secondLayer.value

        val camera = mc.gameRenderer.mainCamera
        val cameraPos = camera.position
        val x = entity.x - cameraPos.x
        val y = entity.y - cameraPos.y
        val z = entity.z - cameraPos.z

        entity.setPos(entity.x, entity.y + ySpeed.value.toDouble() / 50.0, entity.z)

        matrices.pushPose()
        matrices.translate(x.toFloat(), y.toFloat(), z.toFloat())

        var yRotYaw = (alpha / 255f) * 360f * rotSpeed.value
        val aSpeedVal = aSpeed.value
        val rotSpeedVal = rotSpeed.value
        yRotYaw = if (yRotYaw == 0f) 0f else yRotYaw - (((aSpeedVal / 255f) * 360f * rotSpeedVal) * partialTick)

        matrices.mulPose(Axis.YP.rotationDegrees(180f - entity.yBodyRot + yRotYaw))
        prepareScale(matrices)

        modelBase.head.xRot = Math.toRadians(entity.xRot.toDouble()).toFloat()
        modelBase.head.yRot = Math.toRadians((entity.yHeadRot - entity.yBodyRot).toDouble()).toFloat()

        val colorVal = color.value.getColorRGB()
        val renderType = RenderType.entityTranslucent(texture)
        val vertexConsumer = bufferSource.getBuffer(renderType)
        modelBase.renderToBuffer(matrices, vertexConsumer, 15728880, OverlayTexture.NO_OVERLAY, colorVal.red / 255f, colorVal.green / 255f, colorVal.blue / 255f, alpha / 255f)

        matrices.popPose()
    }

    private fun prepareScale(matrixStack: PoseStack) {
        matrixStack.scale(-1.0F, -1.0F, 1.0F)
        matrixStack.scale(1.6f, 1.8f, 1.6f)
        matrixStack.translate(0.0F, -1.501F, 0.0F)
    }

    private class Person(val player: Player, val texture: ResourceLocation, initialAlpha: Int) {
        val modelPlayer = PlayerModel<Player>(
            Minecraft.getInstance().entityModels.bakeLayer(ModelLayers.PLAYER),
            false
        )
        var alpha = initialAlpha

        fun update(list: CopyOnWriteArrayList<Person>, speed: Int) {
            if (alpha <= 0) {
                list.remove(this)
                player.discard()
                return
            }
            alpha -= speed
        }

        fun getAlphaClamped(): Int {
            return alpha.coerceIn(0, 255)
        }
    }
}
