package anpilot.client.features.utility

import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.player.Player
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

open class FakePlayerEntity(player: Player, name: String) : RemotePlayer(
    Minecraft.getInstance().level!!,
    GameProfile(FAKE_UUID, name)
) {
    val playerRef: Player = player

    init {
        setPos(player.x, player.y, player.z)
        yRot = player.yRot
        xRot = player.xRot
        yHeadRot = player.yHeadRot
        yBodyRot = player.yBodyRot
        attackAnim = player.attackAnim

        val modelParts: Byte = player.entityData.get(Player.DATA_PLAYER_MODE_CUSTOMISATION)
        entityData.set(Player.DATA_PLAYER_MODE_CUSTOMISATION, modelParts)

        isShiftKeyDown = player.isShiftKeyDown
        isSwimming = player.isSwimming
        setPose(player.pose)
        health = player.health

        inventory.replaceWith(player.inventory)
        id = CURRENT_ID.incrementAndGet()
    }

    override fun isAlive(): Boolean = true

    fun spawnPlayer() {
        unsetRemoved()
        Minecraft.getInstance().level?.addFreshEntity(this)
    }

    fun despawnPlayer() {
        Minecraft.getInstance().level?.removeEntity(id, RemovalReason.DISCARDED)
        setRemoved(RemovalReason.DISCARDED)
    }

    companion object {
        val FAKE_UUID: UUID = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")
        val CURRENT_ID = AtomicInteger(1000000)
    }
}
