package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.Render2DEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.font.ANFontRenderer
import anpilot.client.renderer.render.ANRender2DEngine
import anpilot.client.compat.projectPointToScreen
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.roundToInt

class ANNameTags : ANBaseModule(
    name = "NameTags",
    description = "穿墙高亮渲染玩家头顶名字标签、网络延迟、生命值及装备道具",
    category = ANModuleCategory.RENDER,
    chineseName = "名字标签"
) {
    val minScale = addSetting(ANSetting("MinScale", 0.5f, 0.1f, 1f))
    val maxScale = addSetting(ANSetting("MaxScale", 1f, 0.5f, 2.0f))
    val height = addSetting(ANSetting("Height", 1f, 0f, 10f))
    val textColor = addSetting(ANSetting("TextColor", ColorGroupSetting(Color(0xFFD128EA.toInt(), true).rgb)))
    val infoColor = addSetting(ANSetting("InfColor", ColorGroupSetting(Color(0xFFF1A10D.toInt(), true).rgb)))
    val plateFill = addSetting(ANSetting("PlateFill", ColorGroupSetting(Color(0xFF28D3EA.toInt(), true).rgb)))
    val plateBorder = addSetting(ANSetting("PlateBorder", ColorGroupSetting(Color(0xAB51F82B.toInt(), true).rgb)))

    private var fontRenderer: ANFontRenderer? = null
    private val armor = arrayOfNulls<ItemStack>(6)
    private val panelHeight = 10f

    @ANEventHandler
    fun onRender2D(event: Render2DEvent) {
        val context = event.context
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val localPlayer = minecraft.player ?: return
        val customFont = fontRenderer ?: ANFontRenderer(minecraft.font).also { fontRenderer = it }
        val tickDelta = event.tickDelta

        for (player in level.players()) {
            if (player === localPlayer && minecraft.options.cameraType == CameraType.FIRST_PERSON) continue
            if (player.isInvisible) continue

            val distance = player.distanceTo(localPlayer)
            val distFactor = 12f / maxOf(distance, 5f)
            val rawScale = 0.5f * distFactor
            val uiScale = rawScale.coerceIn(minScale.value, maxScale.value)
            val scaleFactor = uiScale / 0.5f

            val worldPos = Vec3(
                player.xOld + (player.x - player.xOld) * tickDelta,
                player.yOld + (player.y - player.yOld) * tickDelta + player.bbHeight,
                player.zOld + (player.z - player.zOld) * tickDelta
            )
            val screen = minecraft.gameRenderer.projectPointToScreen(worldPos)
            if (screen.z < 0f || screen.z > 1f) continue

            val x = ((screen.x + 1.0) * 0.5 * context.guiWidth()).toFloat()
            val y = ((1.0 - screen.y) * 0.5 * context.guiHeight()).toFloat()
            if (x.isNaN() || y.isNaN()) continue

            val ping = minecraft.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
            val health = (player.health + player.absorptionAmount).roundToInt()
            val playerName = player.name.string
            val finalString = "$ping $playerName $health"
            val nameWidth = customFont.width(finalString, uiScale)

            val rectWidth = nameWidth + 10f * scaleFactor
            val rectHeight = panelHeight * scaleFactor
            val rectX = x - rectWidth / 2f

            val gap = (2f + height.value * 2f) * scaleFactor
            val rectY = y - gap - rectHeight

            ANRender2DEngine.borderedRoundedRect(
                context,
                rectX,
                rectY,
                rectWidth,
                rectHeight,
                4f * scaleFactor,
                1f * scaleFactor,
                plateFill.value.getColorRGB().rgb,
                plateBorder.value.getColorRGB().rgb
            )

            armor[0] = player.offhandItem
            armor[1] = player.getItemBySlot(EquipmentSlot.HEAD)
            armor[2] = player.getItemBySlot(EquipmentSlot.CHEST)
            armor[3] = player.getItemBySlot(EquipmentSlot.LEGS)
            armor[4] = player.getItemBySlot(EquipmentSlot.FEET)
            armor[5] = player.mainHandItem

            val armorY = rectY - 22f * scaleFactor
            for (position in 0 until 6) {
                val itemStack = armor[position] ?: continue
                if (itemStack.isEmpty) continue
                val armorX = x + (position * 18f * uiScale) - (18f * uiScale * 6) / 2f
                context.pose().pushPose()
                context.pose().translate(armorX, armorY, 0f)
                context.pose().scale(uiScale, uiScale, 1f)
                context.renderItem(itemStack.copy(), 0, 0)
                context.renderItemDecorations(minecraft.font, itemStack, 0, 0)
                context.pose().popPose()
            }

            val textX = x - nameWidth / 2f
            val textY = rectY + 2f * scaleFactor

            customFont.draw(context, ping.toString(), textX, textY, infoColor.value.getColorRGB().rgb, uiScale)
            customFont.draw(
                context,
                playerName,
                textX + customFont.width("$ping ", uiScale),
                textY,
                textColor.value.getColorRGB().rgb,
                uiScale
            )
        }
    }
}
