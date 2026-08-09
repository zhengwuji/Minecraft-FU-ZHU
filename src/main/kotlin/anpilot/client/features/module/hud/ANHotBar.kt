package anpilot.client.features.module.hud

import anpilot.client.api.gui.ANGuiRenderContext
import anpilot.client.features.module.anpilot.ANTheme
import net.minecraft.client.Minecraft
import java.awt.Color

class ANHotBar : ANDraggableHudModule("HotBar", "提供现代化圆角自定义快捷栏视觉展示", "自定义快捷栏", 200f, 400f) {

    override fun renderHudContent(context: ANGuiRenderContext, editor: Boolean) {
        val player = Minecraft.getInstance().player
        setHudBounds(scaled(PANEL_WIDTH), scaled(PANEL_HEIGHT))
        val centerX = (context.width - hudWidth) / 2f
        val centerRatio = centerX / context.width.toFloat()
        if (posX.value != centerRatio) posX.setValueSilent(centerRatio)
        context.borderedRoundedRect(x, y, hudWidth, hudHeight, scaled(10f), scaled(1f), HudColors.panelFillColor, HudColors.panelBorderColor)
        if (player == null) return

        val offhandX = x + scaled(PANEL_PADDING)
        val slotStartX = offhandX + scaled(SLOT_SIZE + OFFHAND_GAP)
        val slotY = y + scaled(SLOT_TOP)
        drawSlot(context, offhandX, slotY, ANTheme.Transparent, HudColors.text2)
        context.item(player.offhandItem, offhandX + scaled(ITEM_INSET), y + scaled(ITEM_TOP), scaled(ITEM_SCALE), true)

        for (slot in 0 until 9) {
            val slotX = slotStartX + scaled(SLOT_SPACING) * slot
            context.item(player.inventory.getItem(slot), slotX + scaled(ITEM_INSET), y + scaled(ITEM_TOP), scaled(ITEM_SCALE), true)
        }

        drawSlot(
            context,
            slotStartX + scaled(SLOT_SPACING) * player.inventory.selected,
            slotY,
            ANTheme.Transparent,
            HudColors.text2,
            SELECTED_BORDER_WIDTH
        )
    }

    private fun drawSlot(
        context: ANGuiRenderContext,
        slotX: Float,
        slotY: Float,
        fillColor: Color,
        borderColor: Color,
        borderWidth: Float = SLOT_BORDER_WIDTH
    ) {
        context.borderedRoundedRect(
            slotX,
            slotY,
            scaled(SLOT_SIZE),
            scaled(SLOT_SIZE),
            scaled(8f),
            scaled(borderWidth),
            fillColor,
            borderColor
        )
    }

    private companion object {
        private const val PANEL_PADDING = 6f
        private const val PANEL_HEIGHT = 30f
        private const val SLOT_SIZE = 24f
        private const val SLOT_SPACING = 24f
        private const val OFFHAND_GAP = 6f
        private const val PANEL_WIDTH = PANEL_PADDING * 2f + SLOT_SIZE + OFFHAND_GAP + SLOT_SPACING * 9f
        private const val SLOT_TOP = 3f
        private const val ITEM_TOP = 6f
        private const val ITEM_INSET = 3.6f
        private const val ITEM_SCALE = 1.05f
        private const val SLOT_BORDER_WIDTH = 1f
        private const val SELECTED_BORDER_WIDTH = 1.7f
    }
}
