package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import java.awt.Color

class ANChams : ANBaseModule(
    name = "Chams",
    description = "穿墙透视渲染实体模型与色彩纹理(Chams)",
    category = ANModuleCategory.RENDER,
    chineseName = "透视贴图"
) {
    val mode = addSetting(ANSetting("Mode", Mode.CHAMS))
    val range = addSetting(ANSetting("Range", 30f, 0f, 250f))
    val shineScale = addSetting(ANSetting("Scale", 1.0f, 0.1f, 2.0f) { mode.value == Mode.SHINE })
    val shineSpeed = addSetting(ANSetting("Speed", 0.5f, 0.0f, 1.0f) { mode.value == Mode.SHINE })
    val players = addSetting(ANSetting("Players", true))
    val monsters = addSetting(ANSetting("Monsters", true))
    val crystals = addSetting(ANSetting("Crystals", true))
    val playerColor = addSetting(ANSetting("PlayerColor", ColorGroupSetting(Color(0x9935D8FF.toInt(), true).rgb)))
    val monsterColor = addSetting(ANSetting("MonsterColor", ColorGroupSetting(Color(0x99FF355D.toInt(), true).rgb)))
    val crystalColor = addSetting(ANSetting("CrystalColor", ColorGroupSetting(Color(0x99F6FF35.toInt(), true).rgb)))
    val outlineColor = addSetting(ANSetting("OutlineColor", ColorGroupSetting(Color(0xFF35D8FF.toInt(), true).rgb)))

    fun shouldRender(entity: Entity?): Boolean {
        entity ?: return false
        val player = Minecraft.getInstance().player ?: return false
        if (!entity.isAlive && entity !is EndCrystal) return false
        if (range.value > 0f && player.distanceToSqr(entity) > range.value * range.value) return false
        return when (entity) {
            is Player -> entity !== player && players.value
            is Monster, is Enemy -> monsters.value
            is EndCrystal -> crystals.value
            else -> false
        }
    }

    fun shouldOverlayTexture(entity: Entity?): Boolean {
        return shouldRender(entity)
    }

    fun colorFor(entity: Entity?): Int {
        return when (entity) {
            is Player -> playerColor.value.getColor()
            is Monster, is Enemy -> monsterColor.value.getColor()
            is EndCrystal -> crystalColor.value.getColor()
            else -> 0
        }
    }

    fun outlineColorFor(entity: Entity?): Int {
        if (!shouldRender(entity) || !hasOutline()) return 0
        return outlineColor.value.toANColor().withAlpha(255).argb
    }

    fun renderType(texture: ResourceLocation): RenderType {
        return RenderType.entityCutout(texture)
    }

    fun armorRenderType(texture: ResourceLocation): RenderType {
        return RenderType.entityCutout(texture)
    }

    private fun hasOutline(): Boolean {
        return outlineColor.value.toANColor().alpha > 0
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    enum class Mode {
        SHINE,
        CHAMS
    }

    private companion object {
        val SHINE_TEXTURE: ResourceLocation = ResourceLocation("anpilotclient", "textures/chams.png")
    }
}
