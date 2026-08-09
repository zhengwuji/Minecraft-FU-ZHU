package anpilot.client.features.gui

import net.minecraft.client.Minecraft
import anpilot.client.compat.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

object ANLeaveGuiState {
    var reason: String = "ANPilot LeaveHelper"

    var playerName: String = ""
        private set
    var health: Float = 0f
        private set
    var maxHealth: Float = 20f
        private set
    var ping: Int = 0
        private set
    var dimension: String = ""
        private set
    var position: String = ""
        private set
    var player: Player? = null
        private set
    var inventory: List<ItemStack> = emptyList()
        private set
    var armor: List<ItemStack> = emptyList()
        private set
    var nearbyPlayers: List<NearbyPlayer> = emptyList()
        private set

    val hasPlayerData: Boolean
        get() = playerName.isNotBlank()

    fun captureFromCurrentPlayer(minecraft: Minecraft) {
        val localPlayer = minecraft.player ?: return
        val level = minecraft.level ?: return

        player = localPlayer
        playerName = localPlayer.name.string
        health = localPlayer.health
        maxHealth = localPlayer.maxHealth.coerceAtLeast(1f)
        ping = minecraft.connection?.getPlayerInfo(localPlayer.uuid)?.latency ?: 0
        dimension = level.dimension().location().toString()
        position = "${localPlayer.blockX}, ${localPlayer.blockY}, ${localPlayer.blockZ}"
        inventory = (0 until localPlayer.inventory.containerSize).map { slot ->
            localPlayer.inventory.getItem(slot).copy()
        }

        nearbyPlayers = level.players()
            .asSequence()
            .filter { it !== localPlayer }
            .take(4)
            .map { other ->
                NearbyPlayer(
                    name = other.name.string,
                    skin = minecraft.connection?.getPlayerInfo(other.uuid)?.skinLocation
                )
            }
            .toList()
    }

    fun clearWorldData() {
        player = null
        inventory = emptyList()
        armor = emptyList()
        nearbyPlayers = emptyList()
        playerName = ""
        health = 0f
        maxHealth = 20f
        ping = 0
        dimension = ""
        position = ""
    }

    data class NearbyPlayer(
        val name: String,
        val skin: Identifier?
    )
}
