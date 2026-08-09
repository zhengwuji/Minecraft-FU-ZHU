package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.ANEventPriority
import anpilot.client.features.event.impl.EventPreSync
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.manager.inventory.SilentSwapType
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments

class ANAutoXP : ANBaseModule(
    name = "AutoXP",
    description = "自动快速朝脚下抛掷经验修补瓶，直到全身装备耐久度恢复指定比例",
    category = ANModuleCategory.COMBAT,
    chineseName = "自动经验瓶"
) {
    val feetXP = addSetting(ANSetting("FeetXP", true))
    val rotate = addSetting(ANSetting("Rotate", RotateMode.SILENT))
    val silent = addSetting(ANSetting("Silent", true))
    val inventory = addSetting(ANSetting("Inventory", true))
    val swapDelay = addSetting(ANSetting("SwapDelay", 100f, 0f, 1000f))
    val antiWaste = addSetting(ANSetting("StopOn", 90f, 0f, 100f))
    val mendingOnly = addSetting(ANSetting("MendingOnly", true))
    val autoDisable = addSetting(ANSetting("AutoDisable", true))
    val multitask = addSetting(ANSetting("Multitask", false))
    val swing = addSetting(ANSetting("Swing", true))

    private var shouldThrow = false
    private var lastUse = 0L

    enum class RotateMode {
        OFF,
        NORMAL,
        SILENT
    }

    override fun onDisable() {
        shouldThrow = false
        Inventory.endSwap()
        Inventory.swapBack()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        disable()
    }

    override fun onTick() {
        val player = mc.player ?: return clear()
        if (mc.level == null || player.isSpectator || player.isCreative) return clear()
        if (player.isUsingItem && !multitask.value) return clear()

        val xpSlot = findExperienceBottle()
        if (xpSlot == null) {
            if (autoDisable.value) disable() else clear()
            return
        }

        if (!needsRepair()) {
            if (autoDisable.value) disable() else clear()
            return
        }

        shouldThrow = true
    }

    @ANEventHandler(priority = ANEventPriority.HIGH)
    fun onPreSync(event: EventPreSync) {
        if (!shouldThrow) return
        shouldThrow = false

        val player = mc.player ?: return
        if (mc.level == null || player.isSpectator || player.isCreative) return
        if (player.isUsingItem && !multitask.value) return

        val now = System.currentTimeMillis()
        if (now - lastUse < swapDelay.value.toLong()) return

        val xpSlot = findExperienceBottle() ?: return
        rotateDown(event)
        if (throwXp(xpSlot)) {
            lastUse = now
        }
    }

    private fun throwXp(xpSlot: XpSlot): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false

        val selected = player.inventory.selected
        val swapType = xpSlot.swapType
        val swapped = when {
            xpSlot.slot == selected -> true
            silent.value -> Inventory.startSwap(xpSlot.slot, swapType)
            swapType == SilentSwapType.HOTBAR -> Inventory.swap(xpSlot.slot, swapBack = false)
            inventory.value && swapType == SilentSwapType.INVENTORY -> Inventory.startSwap(xpSlot.slot, swapType)
            else -> false
        }
        if (!swapped) return false

        try {
            gameMode.useItem(player, InteractionHand.MAIN_HAND)
            if (swing.value) {
                player.swing(InteractionHand.MAIN_HAND)
            }
        } finally {
            when {
                xpSlot.slot == selected -> Unit
                silent.value -> Inventory.endSwap(swapType)
                swapType == SilentSwapType.INVENTORY -> Inventory.endSwap(swapType)
            }
        }
        return true
    }

    private fun rotateDown(event: EventPreSync) {
        if (!feetXP.value || rotate.value == RotateMode.OFF) return
        val player = mc.player ?: return
        val oldYaw = player.yRot
        val oldPitch = player.xRot

        when (rotate.value) {
            RotateMode.NORMAL -> {
                player.xRot = 90.0f
                player.yHeadRot = oldYaw
            }

            RotateMode.SILENT -> {
                player.xRot = 90.0f
                player.yHeadRot = oldYaw

                val previousPostAction = event.postAction
                event.postAction = Runnable {
                    previousPostAction?.run()
                    player.yRot = oldYaw
                    player.xRot = oldPitch
                    player.yHeadRot = oldYaw
                }
            }

            RotateMode.OFF -> Unit
        }
    }

    private fun needsRepair(): Boolean {
        val player = mc.player ?: return false
        return ARMOR_SLOTS
            .asSequence()
            .map { player.getItemBySlot(it) }
            .filter { shouldRepair(it) }
            .any { durabilityPercent(it) < antiWaste.value }
    }

    private fun shouldRepair(stack: ItemStack): Boolean {
        if (stack.isEmpty || !stack.isDamageableItem || stack.damageValue <= 0) return false
        return !mendingOnly.value || Inventory.hasEnchantment(stack, Enchantments.MENDING)
    }

    private fun findExperienceBottle(): XpSlot? {
        val player = mc.player ?: return null

        for (slot in 0 until Inventory.HOTBAR_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.EXPERIENCE_BOTTLE)) {
                return XpSlot(slot, SilentSwapType.HOTBAR)
            }
        }

        if (!inventory.value) return null

        for (slot in Inventory.HOTBAR_SIZE until Inventory.MAIN_SIZE) {
            if (player.inventory.getItem(slot).`is`(Items.EXPERIENCE_BOTTLE)) {
                return XpSlot(slot, SilentSwapType.INVENTORY)
            }
        }

        return null
    }

    private fun durabilityPercent(stack: ItemStack): Float {
        val maxDamage = stack.maxDamage
        if (maxDamage <= 0) return 100.0f
        return ((maxDamage - stack.damageValue) / maxDamage.toFloat()) * 100.0f
    }

    private fun clear() {
        shouldThrow = false
    }

    private data class XpSlot(val slot: Int, val swapType: SilentSwapType)

    private companion object {
        private val ARMOR_SLOTS = listOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
        )
    }
}