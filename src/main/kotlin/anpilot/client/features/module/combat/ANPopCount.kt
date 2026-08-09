package anpilot.client.features.module.combat

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModuleState
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANTickEvent
import anpilot.client.features.event.impl.GameJoinedEvent
import anpilot.client.features.event.impl.GameLeftEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ANPopCount : ANBaseModule(
    name = "PopCount",
    description = "在聊天栏统计并显示敌人爆掉的不死图腾数量",
    category = ANModuleCategory.COMBAT,
    chineseName = "图腾计数",
    defaultState = ANModuleState.ENABLED
) {
    val onlyTarget = addSetting(ANSetting("OnlyTarget", false))
    val announcePops = addSetting(ANSetting("Pops", true))
    val announceDeaths = addSetting(ANSetting("Deaths", true))
    val countSelf = addSetting(ANSetting("CountSelf", false))
    val resetOnDeath = addSetting(ANSetting("ResetDeath", true))
    val cleanup = addSetting(ANSetting("Cleanup", true))

    private val logger = LoggerFactory.getLogger("ANPopCount")
    private val totems = ConcurrentHashMap<UUID, TotemData>()
    private val nameCache = ConcurrentHashMap<UUID, String>()

    override fun onDisable() {
        clearAll()
    }

    @ANEventHandler
    fun onGameJoined(event: GameJoinedEvent) {
        clearAll()
    }

    @ANEventHandler
    fun onGameLeft(event: GameLeftEvent) {
        clearAll()
    }

    @ANEventHandler
    fun onTick(event: ANTickEvent) {
        if (!cleanup.value) return

        val level = Minecraft.getInstance().level ?: return
        val knownPlayers = level.players().associateBy { it.uuid }

        totems.keys.removeIf { uuid ->
            val player = knownPlayers[uuid]
            val shouldRemove = player == null || player.isDeadOrDying
            if (shouldRemove) {
                nameCache.remove(uuid)
            }
            shouldRemove
        }
    }

    @ANEventHandler
    fun onPacket(event: PacketEvent.Receive) {
        val packet = event.packet as? ClientboundEntityEventPacket ?: return
        mc.execute { handleEntityEvent(packet) }
    }

    fun getPopCount(uuid: UUID): Int = totems[uuid]?.pops ?: 0

    fun getLastPopTime(uuid: UUID): Long = totems[uuid]?.lastPopTime ?: -1L

    fun getCachedName(uuid: UUID): String? = nameCache[uuid]

    fun resetPopCount(uuid: UUID) {
        totems.remove(uuid)
        nameCache.remove(uuid)
    }

    fun clearAll() {
        totems.clear()
        nameCache.clear()
    }

    private fun handleEntityEvent(packet: ClientboundEntityEventPacket) {
        val level = mc.level ?: return
        val localPlayer = mc.player ?: return
        val entity = packet.getEntity(level) as? Player ?: return
        val uuid = entity.uuid
        val name = entity.name.string

        nameCache[uuid] = name

        when (packet.eventId) {
            35.toByte() -> handleTotemPop(mc, localPlayer, entity, uuid, name)
            EntityEvent.DEATH -> handleDeath(mc, localPlayer, entity, uuid, name)
        }
    }

    private fun handleTotemPop(
        mc: Minecraft,
        localPlayer: Player,
        entity: Player,
        uuid: UUID,
        name: String
    ) {
        if (entity === localPlayer && !countSelf.value) return
        val pops = (totems[uuid]?.pops ?: 0) + 1
        totems[uuid] = TotemData(System.currentTimeMillis(), pops)

        if (shouldShowFor(localPlayer, uuid)) {
            sendChat(mc, popMessage(name, pops))
        }
    }

    private fun handleDeath(
        mc: Minecraft,
        localPlayer: Player,
        entity: Player,
        uuid: UUID,
        name: String
    ) {
        val pops = totems.remove(uuid)?.pops ?: 0
        if (pops > 0 && shouldShowFor(localPlayer, uuid)) {
            sendChat(mc, deathMessage(name, pops))
        }
    }

    private fun shouldShowFor(localPlayer: Player, uuid: UUID): Boolean {
        if (!onlyTarget.value) return true
        return findTarget(localPlayer)?.uuid == uuid
    }

    private fun sendChat(mc: Minecraft, message: Component) {
        mc.gui.chat.addMessage(message)
    }

    private fun popMessage(name: String, pops: Int): Component {
        return Component.literal(name).withStyle(ChatFormatting.GRAY)
            .append(Component.literal(" popped ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(pops.toString()).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" ${totemWord(pops)}").withStyle(ChatFormatting.WHITE))
    }

    private fun deathMessage(name: String, pops: Int): Component {
        return Component.literal(name).withStyle(ChatFormatting.GRAY)
            .append(Component.literal(" died after popping ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(pops.toString()).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" ${totemWord(pops)}").withStyle(ChatFormatting.WHITE))
    }

    private fun totemWord(pops: Int): String {
        return if (pops == 1) "totem" else "totems"
    }

    private fun findTarget(localPlayer: Player): Player? {
        val level = Minecraft.getInstance().level ?: return null
        var best: Player? = null
        var bestDist = Double.MAX_VALUE

        for (player in level.players()) {
            if (player === localPlayer) continue
            if (!player.isAlive) continue

            val dist = localPlayer.distanceToSqr(player)
            if (dist < bestDist) {
                bestDist = dist
                best = player
            }
        }

        return best
    }

    private data class TotemData(val lastPopTime: Long, val pops: Int)
}
