package anpilot.client.features.module.render

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.event.impl.Render2DEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.font.ANFontRenderer
import anpilot.client.renderer.render.ANRender2DEngine
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import anpilot.client.compat.projectPointToScreen
import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerModelPart
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ANLogOutPoints : ANBaseModule(
    name = "LogOutPoints",
    description = "标记附近玩家退出游戏时的离线位置坐标并显示投影残影",
    category = ANModuleCategory.RENDER,
    chineseName = "登出记录"
), ANWorldRenderModule {

    val range = addSetting(ANSetting("Range", 200f, 10f, 500f))
    val scale = addSetting(ANSetting("Scale", 1.0f, 0.2f, 3.0f))
    val renderBox = addSetting(ANSetting("RenderBox", true))
    val renderPlayer = addSetting(ANSetting("RenderPlayer", true))
    val boxColor = addSetting(ANSetting("BoxColor", ColorGroupSetting(Color(255, 60, 60, 180).rgb)))
    val fillColor = addSetting(ANSetting("FillColor", ColorGroupSetting(Color(255, 60, 60, 40).rgb)))
    val textColor = addSetting(ANSetting("TextColor", ColorGroupSetting(Color(255, 255, 255, 255).rgb)))

    private val logoutSpots = CopyOnWriteArrayList<LogoutSpot>()
    private val playerInfoCache = ConcurrentHashMap<UUID, CachedPlayerInfo>()
    private var fontRenderer: ANFontRenderer? = null

    override fun onDisable() {
        logoutSpots.forEach { it.entity?.despawnPlayer() }
        logoutSpots.clear()
        playerInfoCache.clear()
    }

    override fun onTick() {
        val level = mc.level ?: return
        val player = mc.player ?: return

        for (p in level.players()) {
            if (p === player) continue
            playerInfoCache[p.uuid] = CachedPlayerInfo(
                p.gameProfile,
                p.position(),
                p.yRot,
                p.xRot,
                p.yHeadRot,
                p.yBodyRot,
                p.health.toInt(),
                p.bbWidth.toDouble(),
                p.bbHeight.toDouble(),
                LogoutPlayerEntity.getModelParts(p)
            )
        }

        val maxDistSq = range.value * range.value
        logoutSpots.removeIf { spot ->
            val distSq = player.distanceToSqr(spot.x, spot.y, spot.z)
            if (distSq > maxDistSq) {
                spot.entity?.despawnPlayer()
                spot.entity = null
                true
            } else {
                false
            }
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        val level = mc.level ?: return

        when (val packet = event.packet) {
            is ClientboundPlayerInfoUpdatePacket -> {
                for (entry in packet.newEntries()) {
                    val profile = entry.profile ?: continue
                    val spot = logoutSpots.firstOrNull { it.uuid == profile.id }
                    if (spot != null) {
                        spot.entity?.despawnPlayer()
                        logoutSpots.remove(spot)
                    }
                }
            }

            is ClientboundPlayerInfoRemovePacket -> {
                for (uuid in packet.profileIds) {
                    val info = playerInfoCache.remove(uuid) ?: continue
                    if (level.getPlayerByUUID(uuid) != null) continue

                    val spot = LogoutSpot(
                        info.profile.id,
                        info.profile.name,
                        info.pos.x,
                        info.pos.y,
                        info.pos.z,
                        info.yaw,
                        info.pitch,
                        info.yawHead,
                        info.yawBody,
                        info.health,
                        info.width,
                        info.height,
                        info.modelParts,
                        info.profile
                    )

                    if (renderPlayer.value) {
                        val entity = LogoutPlayerEntity(level, info.profile, spot)
                        spot.entity = entity
                        mc.execute {
                            entity.spawnPlayer()
                        }
                    }

                    logoutSpots.add(spot)
                }
            }
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (!renderBox.value || logoutSpots.isEmpty()) return

        val line = boxColor.value.toANColor()
        val fill = fillColor.value.toANColor()

        for (spot in logoutSpots) {
            val halfW = spot.width / 2.0
            val box = AABB(
                spot.x - halfW, spot.y, spot.z - halfW,
                spot.x + halfW, spot.y + spot.height, spot.z + halfW
            )
            ANRender3DEngine.box(context, box, line, fill)
        }
    }

    @ANEventHandler
    fun onRender2D(event: Render2DEvent) {
        if (logoutSpots.isEmpty()) return
        val context = event.context
        val localPlayer = mc.player ?: return
        val customFont = fontRenderer ?: ANFontRenderer(mc.font).also { fontRenderer = it }

        for (spot in logoutSpots) {
            val worldPos = Vec3(spot.x, spot.y + spot.height + 0.3, spot.z)

            val distance = worldPos.distanceTo(localPlayer.eyePosition)
            val distFactor = 12f / maxOf(distance.toFloat(), 5f)
            val rawScale = 0.5f * distFactor
            val uiScale = (rawScale * scale.value).coerceIn(0.1f, 2.0f)
            val scaleFactor = uiScale / 0.5f

            val screen = mc.gameRenderer.projectPointToScreen(worldPos)
            if (screen.z < 0f || screen.z > 1f) continue

            val x = ((screen.x + 1.0) * 0.5 * context.guiWidth()).toFloat()
            val y = ((1.0 - screen.y) * 0.5 * context.guiHeight()).toFloat()
            if (x.isNaN() || y.isNaN()) continue

            val healthText = " ${spot.health}"
            val finalString = "${spot.name}$healthText"
            val nameWidth = customFont.width(finalString, uiScale)

            val rectWidth = nameWidth + 10f * scaleFactor
            val rectHeight = 10f * scaleFactor
            val rectX = x - rectWidth / 2f
            val rectY = y - rectHeight

            ANRender2DEngine.borderedRoundedRect(
                context,
                rectX,
                rectY,
                rectWidth,
                rectHeight,
                4f * scaleFactor,
                1f * scaleFactor,
                fillColor.value.getColorRGB().rgb,
                boxColor.value.getColorRGB().rgb
            )

            val textX = x - nameWidth / 2f
            val textY = rectY + 2f * scaleFactor

            customFont.draw(context, spot.name, textX, textY, textColor.value.getColorRGB().rgb, uiScale)
            customFont.draw(
                context,
                healthText,
                textX + customFont.width(spot.name, uiScale),
                textY,
                0xFFFF5555.toInt(),
                uiScale
            )
        }
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private data class CachedPlayerInfo(
        val profile: GameProfile,
        val pos: Vec3,
        val yaw: Float,
        val pitch: Float,
        val yawHead: Float,
        val yawBody: Float,
        val health: Int,
        val width: Double,
        val height: Double,
        val modelParts: Byte
    )

    class LogoutSpot(
        val uuid: UUID,
        val name: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val yawHead: Float,
        val yawBody: Float,
        val health: Int,
        val width: Double,
        val height: Double,
        val modelParts: Byte,
        val profile: GameProfile,
        var entity: LogoutPlayerEntity? = null
    )

    class LogoutPlayerEntity : AbstractClientPlayer {
        constructor(player: Player) : super(player.level() as ClientLevel, player.gameProfile) {
            setPos(player.x, player.y, player.z)
            yRot = player.yRot
            xRot = player.xRot
            yHeadRot = player.yHeadRot
            yBodyRot = player.yBodyRot
            attackAnim = player.attackAnim

            val modelParts = player.entityData.get(DATA_PLAYER_MODE_CUSTOMISATION)
            entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, modelParts)

            isShiftKeyDown = player.isShiftKeyDown
            isSwimming = player.isSwimming
            setPose(player.pose)
            health = player.health

            inventory.replaceWith(player.inventory)
            id = CURRENT_ID.incrementAndGet()
        }

        constructor(level: ClientLevel, profile: GameProfile, spot: LogoutSpot) : super(level, profile) {
            setPos(spot.x + spot.width / 2.0, spot.y, spot.z + spot.width / 2.0)
            yRot = spot.yaw
            xRot = spot.pitch
            yHeadRot = spot.yawHead
            yBodyRot = spot.yawBody

            entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, spot.modelParts)

            health = spot.health.toFloat()
            id = CURRENT_ID.incrementAndGet()
        }

        override fun isAlive(): Boolean = true
        override fun shouldShowName(): Boolean = false
        override fun isCustomNameVisible(): Boolean = false

        fun spawnPlayer() {
            unsetRemoved()
            Minecraft.getInstance().level?.addFreshEntity(this)
        }

        fun despawnPlayer() {
            Minecraft.getInstance().level?.removeEntity(id, Entity.RemovalReason.DISCARDED)
            setRemoved(Entity.RemovalReason.DISCARDED)
        }

        companion object {
            val CURRENT_ID = AtomicInteger(2000000)
            
            fun getModelParts(player: Player): Byte {
                return player.entityData.get(DATA_PLAYER_MODE_CUSTOMISATION)
            }
        }
    }
}
