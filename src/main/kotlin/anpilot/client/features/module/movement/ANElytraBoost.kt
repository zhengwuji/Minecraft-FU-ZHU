package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.player.ANAutoArmour
import anpilot.client.features.setting.ANSetting
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

class ANElytraBoost : ANBaseModule(
    name = "ElytraBoost",
    description = "利用鞘翅起飞机制向上弹射加速",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "鞘翅弹射"
) {
    val boost = addSetting(ANSetting("Boost", 2.88, 0.1, 5.0))

    private var disabledAutoArmour = false
    private var boosted = false
    private var initiated = false
    private var restoreSlot = -1
    private var hadChestItem = false

    override fun onEnable() {
        disabledAutoArmour = false
        boosted = false
        initiated = false
        restoreSlot = -1
        hadChestItem = false
    }

    override fun onDisable() {
        restoreAutoArmour()
    }

    override fun onTick() {
        val player = mc.player ?: return
        val connection = mc.connection ?: return

        val onGround = player.onGround()
        val equipped = player.getItemBySlot(EquipmentSlot.CHEST).`is`(Items.ELYTRA)

        if (!equipped && !initiated) {
            val elytraSlot = findInventorySlot(Items.ELYTRA)
            if (elytraSlot == -1) {
                disable("Couldn't find an elytra.")
                return
            }

            pauseAutoArmour()
            val chestStack = player.getItemBySlot(EquipmentSlot.CHEST)
            hadChestItem = !chestStack.isEmpty
            restoreSlot = toMenuSlot(elytraSlot)
            moveToChestSlot(restoreSlot)
            return
        }

        if (onGround && !initiated && equipped) {
            player.jumpFromGround()
            initiated = true
            return
        }

        if (!onGround && equipped && !boosted) {
            connection.send(
                ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                )
            )
            val velocity = player.deltaMovement
            player.deltaMovement = Vec3(0.0, velocity.y + boost.value, 0.0)
            connection.send(
                ServerboundMovePlayerPacket.PosRot(
                    player.x,
                    player.y,
                    player.z,
                    player.yRot,
                    -90.0f,
                    false
                )
            )
            boosted = true
            return
        }

        restoreChestSlot()
        restoreAutoArmour()
        boosted = false
        initiated = false
        disable()
    }

    private fun pauseAutoArmour() {
        val autoArmour = ANServiceRegistry.runtime.moduleManager.get("AutoArmour") as? ANAutoArmour ?: return
        if (autoArmour.enabled) {
            autoArmour.disable()
            disabledAutoArmour = true
        }
    }

    private fun restoreAutoArmour() {
        if (!disabledAutoArmour) return
        val autoArmour = ANServiceRegistry.runtime.moduleManager.get("AutoArmour") as? ANAutoArmour
        autoArmour?.enable()
        disabledAutoArmour = false
    }

    private fun restoreChestSlot() {
        val player = mc.player ?: return
        if (!hadChestItem || restoreSlot == -1) return
        if (!player.getItemBySlot(EquipmentSlot.CHEST).`is`(Items.ELYTRA)) return
        moveToChestSlot(restoreSlot)
        hadChestItem = false
        restoreSlot = -1
    }

    private fun moveToChestSlot(fromMenuSlot: Int) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, fromMenuSlot, 0, ClickType.PICKUP, player)
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, CHEST_MENU_SLOT, 0, ClickType.PICKUP, player)
        gameMode.handleInventoryMouseClick(player.containerMenu.containerId, fromMenuSlot, 0, ClickType.PICKUP, player)
    }

    private fun findInventorySlot(item: Item): Int {
        val player = mc.player ?: return -1
        for (slot in 0 until 36) {
            if (player.inventory.getItem(slot).`is`(item)) return slot
        }
        return -1
    }

    private fun toMenuSlot(slot: Int): Int = if (slot < 9) InventoryMenu.USE_ROW_SLOT_START + slot else slot

    private companion object {
        const val CHEST_ARMOR_ID = 2
        val CHEST_MENU_SLOT: Int = InventoryMenu.ARMOR_SLOT_START + (3 - CHEST_ARMOR_ID)
    }
}
