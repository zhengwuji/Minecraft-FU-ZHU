package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.AmbientCreature
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.npc.AbstractVillager
import java.awt.Color

class ANEsp : ANBaseModule(
    name = "Esp",
    description = "穿墙透视高亮周围玩家与生物的外框",
    category = ANModuleCategory.RENDER,
    chineseName = "透视"
), ANWorldRenderModule {
    val monsters = addSetting(ANSetting("Monsters", true))
    val mobYLimit = addSetting(ANSetting("MobYLimit", 10f, 20f, 100f))
    val animals = addSetting(ANSetting("Animals", false))
    val villagers = addSetting(ANSetting("Villagers", false))
    val ambient = addSetting(ANSetting("Ambient", false))
    val fill = addSetting(ANSetting("Fill", true))
    val outline = addSetting(ANSetting("Outline", true))
    val monsterColor = addSetting(ANSetting("MonsterColor", ColorGroupSetting(Color(0x99D22BF8.toInt(), true).rgb)))
    val animalsColor = addSetting(ANSetting("AnimalsColor", ColorGroupSetting(Color(0x99EAC328.toInt(), true).rgb)))
    val villagerColor = addSetting(ANSetting("VillagerColor", ColorGroupSetting(Color(0x991FF1D8.toInt(), true).rgb)))
    val ambientColor = addSetting(ANSetting("AmbientColor", ColorGroupSetting(Color(0x9950F6FF.toInt(), true).rgb)))

    override fun renderWorld(context: LevelRenderContext) {
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (!fill.value && !outline.value) return

        for (entity in level.entitiesForRendering()) {
            if (entity === localPlayer || !shouldRender(entity)) continue
            val color = colorOf(entity)
            val fillColor = if (fill.value) color else null
            val lineColor = if (outline.value) color.withAlpha(255) else color.withAlpha(0)
            ANRender3DEngine.box(context, entity.boundingBox.inflate(0.03), lineColor, fillColor)
        }
    }

    private fun shouldRender(entity: Entity): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return when (entity) {
            is Monster -> monsters.value && entity.y < player.y + mobYLimit.value && entity.y > player.y - mobYLimit.value
            is Animal -> animals.value
            is AbstractVillager -> villagers.value
            is AmbientCreature -> ambient.value
            else -> false
        }
    }

    private fun colorOf(entity: Entity): ANColor {
        return when (entity) {
            is Monster -> monsterColor.value.toANColor()
            is Animal -> animalsColor.value.toANColor()
            is AbstractVillager -> villagerColor.value.toANColor()
            is AmbientCreature -> ambientColor.value.toANColor()
            else -> ANColor.rgb(255, 255, 0)
        }
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())
}
