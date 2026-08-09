package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANTickEvent
import anpilot.client.features.manager.inventory.Inventory
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.utility.ANTimer
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.Items
import java.util.function.Consumer
import net.minecraft.world.item.Item

class ANMiddleClick : ANBaseModule(
    name = "MiddleClick",
    description = "按下鼠标中键快速抛掷末影珍珠或使用烟花火箭",
    category = ANModuleCategory.MISC,
    chineseName = "快捷中键"
) {
    private val silent = addSetting(ANSetting("Silent", true))
    private val inventory = addSetting(ANSetting("Inventory", true))
    private val swapDelay = addSetting(ANSetting("SwapDelay", 100f, 0f, 1000f))

    private val timer = ANTimer()

    @ANEventHandler
    fun onTick(event: ANTickEvent) {
        val player = mc.player ?: return

        if (mc.screen == null) {
            if (mc.options.keyPickItem.isDown) {
                if (player.isFallFlying && !mc.options.keySprint.isDown) {
                    Action.Firework.doAction(event)
                } else {
                    Action.Pearl.doAction(event)
                }
            }
        }
    }

    inner class PearlThread(
        private val player: LocalPlayer,
        private val epSlot: Int,
        private val originalSlot: Int,
        private val delay: Int,
        private val isInventoryMode: Boolean
    ) : Thread() {
        override fun run() {
            if (!isInventoryMode) {
                Inventory.switchTo(epSlot)
                toSleep(delay)
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                mc.connection?.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
                toSleep(delay)
                Inventory.switchTo(originalSlot)
            } else {
                mc.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, epSlot, originalSlot, ClickType.SWAP, player)
                toSleep(delay)
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                mc.connection?.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
                toSleep(delay)
                mc.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, epSlot, originalSlot, ClickType.SWAP, player)
            }
        }
    }

    enum class Action(private val r: Consumer<ANTickEvent>) {
        Firework(Consumer { instance?.performAction(Items.FIREWORK_ROCKET, 250L) }),
        Pearl(Consumer { instance?.performAction(Items.ENDER_PEARL, 500L) }),
        None(Consumer { });

        fun doAction(e: ANTickEvent) {
            r.accept(e)
        }
    }

    private fun performAction(item: Item, cooldown: Long) {
        val player = mc.player ?: return
        if (!timer.every(cooldown)) return

        val hotbarSlot = Inventory.findItemInHotbar(item).slot
        val isSilent = silent.value
        val useInv = inventory.value

        if (isSilent) {
            if (!useInv || (useInv && hotbarSlot != -1)) {
                val originalSlot = player.inventory.selected
                if (hotbarSlot != -1) {
                    player.inventory.selected = hotbarSlot
                    mc.connection?.send(ServerboundSetCarriedItemPacket(hotbarSlot))
                    mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                    mc.connection?.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
                    player.inventory.selected = originalSlot
                    mc.connection?.send(ServerboundSetCarriedItemPacket(originalSlot))
                }
            } else {
                val invSlot = Inventory.findItemInInventory(item).slot
                if (invSlot != -1) {
                    mc.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, invSlot, player.inventory.selected, ClickType.SWAP, player)
                    mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                    mc.connection?.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
                    mc.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, invSlot, player.inventory.selected, ClickType.SWAP, player)
                }
            }
        } else {
            if (!useInv || (useInv && hotbarSlot != -1)) {
                if (hotbarSlot != -1)
                    PearlThread(player, hotbarSlot, player.inventory.selected, swapDelay.value.toInt(), false).start()
            } else {
                val invSlot = Inventory.findItemInInventory(item).slot
                if (invSlot != -1)
                    PearlThread(player, invSlot, player.inventory.selected, swapDelay.value.toInt(), true).start()
            }
        }
    }

    companion object {
        private var instance: ANMiddleClick? = null

        private fun toSleep(delay: Int) {
            try { Thread.sleep(delay.toLong()) } catch (_: Exception) {}
        }
    }

    init {
        instance = this
    }
}
