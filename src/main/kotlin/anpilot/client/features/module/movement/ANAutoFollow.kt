package anpilot.client.features.module.movement

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ClientRotationEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.manager.rotation.RotationUtil
import anpilot.client.features.manager.rotation.Rotation
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items

class ANAutoFollow : ANBaseModule(
    name = "AutoFollow",
    description = "自动飞行并跟在目标玩家身后",
    category = ANModuleCategory.MOVEMENT,
    chineseName = "自动跟随"
) {
    val fireworkDelay = addSetting(ANSetting("FireworkDelay", 2.0f, 0.5f, 10.0f))

    private var target: Player? = null
    private var fireworkTimer = 0

    override fun onEnable() {
        target = null
        fireworkTimer = 0
        
        val player = mc.player ?: return
        val players = mc.level?.players() ?: return
        
        target = players
            .filter { it != player && it.isAlive }
            .minByOrNull { player.distanceToSqr(it) }

        if (target == null) {
            disable("Could not find any player to follow.")
        } else {
            sendClientMessage("Targeting: ${target?.name?.string}")
        }
    }

    override fun onDisable() {
        target = null
    }

    override fun onTick() {
        val player = mc.player ?: return
        val targetEntity = target ?: return

        if (!targetEntity.isAlive || targetEntity.isRemoved) {
            disable("Target player is no longer alive or valid.")
            return
        }

        if (player.isFallFlying) {
            fireworkTimer++
            if (fireworkTimer >= fireworkDelay.value * 20) {
                useFirework()
                fireworkTimer = 0
            }
        } else {
            fireworkTimer = 0
        }
    }

    @ANEventHandler
    fun onClientRotation(event: ClientRotationEvent) {
        val player = mc.player ?: return
        val targetEntity = target ?: return

        if (targetEntity.isAlive && !targetEntity.isRemoved) {
            val rotations = RotationUtil.getRotationsTo(
                player.eyePosition,
                targetEntity.boundingBox.center
            )
            event.cancel()
            event.rotation = Rotation(rotations[0], rotations[1])
        }
    }

    private fun useFirework(): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        val connection = mc.connection ?: return false
        val inventory = player.inventory
        val originalSlot = inventory.selected
        var fireworkSlot = -1
        
        for (slot in 0 until 9) {
            if (inventory.getItem(slot).item == Items.FIREWORK_ROCKET) {
                fireworkSlot = slot
                break
            }
        }
        
        if (fireworkSlot == -1) return false

        if (originalSlot != fireworkSlot) {
            inventory.selected = fireworkSlot
            connection.send(ServerboundSetCarriedItemPacket(fireworkSlot))
        }

        gameMode.useItem(player, InteractionHand.MAIN_HAND)
        connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))

        if (originalSlot != fireworkSlot) {
            inventory.selected = originalSlot
            connection.send(ServerboundSetCarriedItemPacket(originalSlot))
        }
        return true
    }
}
