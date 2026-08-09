package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.manager.ANFriendManager
import anpilot.client.features.module.anpilot.ANTheme
import anpilot.client.features.module.combat.ANPopCount
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items


class ANTargetInfo : ANDraggableHudModule("TargetInfo", "附近最近敌方目标的血量、护甲及状态信息", "玩家目标", 180f, 10f) {

    private val totemStack: ItemStack by lazy { ItemStack(Items.TOTEM_OF_UNDYING) }

    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        setHudBounds(scaled(250f), scaled(45f))

        val target = closestPlayer(128.0)


        val fill = if (target != null && ANFriendManager.isFriend(target.name.string)) ANTheme.Green else HudColors.panelFillColor
        context.borderedRoundedRect(visualX, visualY, hudWidth, hudHeight, scaled(10f), scaled(1.5f), fill, HudColors.panelBorderColor)

        if (target == null) {
            if (editor) context.text("TargetInfo", visualX + scaled(8f), visualY + scaled(14f), ANTheme.White.rgb, hudScale)
            return
        }


        val info = mc.connection?.getPlayerInfo(target.uuid)
        if (info != null) context.head(anpilot.client.compat.PlayerSkin(info.skinLocation), visualX + scaled(5f), visualY + scaled(8f), scaled(30f), ANTheme.White)

        val nameX = visualX + scaled(40f)
        val nameY = visualY + scaled(5f)
        context.text(target.name.string, nameX, nameY, ANTheme.White.rgb, hudScale)

        val healthX = visualX + scaled(125f)
        val health = (target.health + target.absorptionAmount).toInt()
        val healthColor = when {
            health > 16 -> ANTheme.Green.rgb
            health > 8 -> ANTheme.Yellow.rgb
            else -> ANTheme.Red.rgb
        }
        context.text("${health}hp", healthX, nameY, healthColor, hudScale)

        val armor = arrayOf(
            target.offhandItem,
            target.getItemBySlot(EquipmentSlot.HEAD),
            target.getItemBySlot(EquipmentSlot.CHEST),
            target.getItemBySlot(EquipmentSlot.LEGS),
            target.getItemBySlot(EquipmentSlot.FEET),
            target.mainHandItem
        )
        val itemScale = scaled(1.2f)
        val itemStartX = visualX + scaled(40f)
        val itemY = visualY + scaled(20f)
        for (i in armor.indices) {
            val stack = armor[i]
            if (!stack.isEmpty) {
                context.item(stack, itemStartX + i * scaled(18f), itemY, itemScale, true)
            }
        }

        val totemX = visualX + scaled(215f)
        val totemY = visualY + scaled(10f)
        context.borderedRoundedRect(x + scaled(210f), y + scaled(5f), scaled(34f), scaled(34f), scaled(8f), scaled(1.5f), HudColors.panelBorderColor, ANTheme.Yellow)
        context.item(totemStack, totemX, totemY, scaled(1.5f), true)

        val totemPopModule = getTotemPopModule()
        if (totemPopModule != null && totemPopModule.enabled) {

            val pops = totemPopModule.getPopCount(target.uuid)
            if (pops > 0) {
                val popText = "x$pops"
                val popColor = if (pops >= 3) ANTheme.Red.rgb else ANTheme.Yellow.rgb
                context.text(popText, totemX + scaled(20f), totemY + scaled(8f), popColor, hudScale)
            }
        }
    }

    private fun getTotemPopModule(): ANPopCount? {
        return try {
            ANServiceRegistry.runtime.moduleManager.get("PopCount") as? ANPopCount
        } catch (_: Exception) { null }
    }

    private fun closestPlayer(maxDistance: Double): Player? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        return level.players()
            .filter { it !== player }
            .filter { player.distanceToSqr(it) <= maxDistance * maxDistance }
            .minByOrNull { player.distanceToSqr(it) }
    }
}
