package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.awt.Color

class ANAirPlace : ANBaseModule(
    name = "AirPlace",
    description = "允许在准星指向的虚空或空中位置直接凭空放置方块",
    category = ANModuleCategory.PLAYER,
    chineseName = "空中放置"
), ANWorldRenderModule {
    val grimBypass = addSetting(ANSetting("Grim", false))
    val boxFill = addSetting(ANSetting("Fill", false))
    val range = addSetting(ANSetting("Range", 5f, 0f, 5f))
    val boxColor = addSetting(ANSetting("BoxColor", ColorGroupSetting(Color(0xAB632BF8.toInt(), true).rgb)))

    private var hit: BlockHitResult? = null
    private var cooldown = 0


    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val gameMode = minecraft.gameMode ?: return

        if (cooldown > 0) {
            cooldown--
        }

        hit = minecraft.hitResult as? BlockHitResult
        val target = hit ?: return
        if (target.type != HitResult.Type.MISS && target.type != HitResult.Type.BLOCK) return
        if (player.mainHandItem.item !is BlockItem) return
        if (!minecraft.options.keyUse.isDown || cooldown > 0) return

        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, target)
        player.swing(InteractionHand.MAIN_HAND)
        cooldown = 2
    }

    override fun renderWorld(context: LevelRenderContext) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val target = hit ?: return
        if (target.type != HitResult.Type.MISS && target.type != HitResult.Type.BLOCK) return
        if (player.mainHandItem.item !is BlockItem) return

        ANRender3DEngine.box(context, AABB(target.blockPos), ANColor.fromArgb(boxColor.value.getColor()))
    }
}
