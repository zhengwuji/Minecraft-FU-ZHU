package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.api.module.ANModule
import anpilot.client.bootstrap.ANServiceRegistry
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.Render2DEvent
import anpilot.client.features.manager.ANSoundManager
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.render.ANRender2DEngine
import anpilot.client.renderer.font.ANFontRenderer
import net.minecraft.client.Minecraft
import anpilot.client.compat.GuiGraphicsExtractor
import anpilot.client.compat.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.awt.Color
import java.util.UUID
import kotlin.math.roundToInt
import net.minecraft.sounds.SoundEvent

class ANNotifier : ANBaseModule(
    name = "Notifier",
    description = "在屏幕上弹出提示，提醒敌人接近与装备低耐久警告",
    category = ANModuleCategory.MISC,
    chineseName = "消息通知"
) {
    private val fontRenderer by lazy { ANFontRenderer(Minecraft.getInstance().font) }
    val posY = addSetting(ANSetting("PosY", 12f, 0f, 80f))
    val moduleToggle = addSetting(ANSetting("ModuleToggle", true))
    val playerEnter = addSetting(ANSetting("PlayerEnter", true))
    val range = addSetting(ANSetting("Range", 128f, 16f, 256f){playerEnter.value})
    val durabilityWarn = addSetting(ANSetting("DurabilityWarn", true))
    val durabilityLeft = addSetting(ANSetting("DurabilityLeft", 20f, 1f, 100f))
    val textColor = addSetting(ANSetting("TextColor", ColorGroupSetting(Color(0xFFE7F6FF.toInt(), true).rgb)))
    val infoColor = addSetting(ANSetting("InfColor", ColorGroupSetting(Color(0xFFFFC247.toInt(), true).rgb)))
    val plateFill = addSetting(ANSetting("PlateFill", ColorGroupSetting(Color(0xD9142233.toInt(), true).rgb)))
    val plateBorder = addSetting(ANSetting("PlateBorder", ColorGroupSetting(Color(0xFF28D3EA.toInt(), true).rgb)))
    val delay = addSetting(ANSetting("Delay", 2f, 1f, 10f))

    private val visiblePlayers = mutableSetOf<UUID>()
    private val notifications = mutableListOf<Notification>()
    private val durabilityCooldowns = mutableMapOf<String, Long>()
    private val moduleStates = mutableMapOf<String, Boolean>()

    override fun onEnable() {
        snapshotModuleStates()
    }

    override fun onDisable() {
        visiblePlayers.clear()
        notifications.clear()
        durabilityCooldowns.clear()
        moduleStates.clear()
    }

    override fun onTick() {
        if (moduleToggle.value) detectModuleToggles() else snapshotModuleStates()
        if (playerEnter.value) detectPlayerEntries()
        if (durabilityWarn.value) detectLowDurability()
    }

    @ANEventHandler
    fun onRender2D(event: Render2DEvent) {
        renderNotifications(event.context)
    }

    private fun detectPlayerEntries() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val rangeSq = range.value.toDouble() * range.value.toDouble()
        val current = level.players()
            .asSequence()
            .filter { it !== player && !it.isSpectator }
            .filter { player.distanceToSqr(it) <= rangeSq }
            .filter { player.hasLineOfSight(it) }
            .toList()

        current
            .filter { it.uuid !in visiblePlayers }
            .forEach { entered ->
                enqueue(
                    key = "player:${entered.uuid}",
                    type = NotificationType.PLAYER_ENTER,
                    detail = entered.name.string,
                    stack = ItemStack.EMPTY,
                    skinTexture = minecraft.connection?.getPlayerInfo(entered.uuid)?.skinLocation,
                    sound = SoundEvents.EXPERIENCE_ORB_PICKUP,
                    volume = 1f,
                    pitch = 1f,
                    alertSound = ANSoundManager.AlertSound.PlayerEnter
                )
            }

        visiblePlayers.clear()
        visiblePlayers += current.map { it.uuid }
    }

    private fun detectLowDurability() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val now = System.currentTimeMillis()
        durabilityCooldowns.entries.removeIf { (_, expiresAt) -> now >= expiresAt + DURABILITY_RESET_MS }

        durabilityTargets(player).forEach { target ->
            val stack = target.stack
            if (stack.isEmpty || !stack.isDamageableItem) return@forEach
            val remaining = stack.maxDamage - stack.damageValue
            if (remaining > durabilityLeft.value.toInt()) {
                durabilityCooldowns.remove(target.key)
                return@forEach
            }
            if ((durabilityCooldowns[target.key] ?: 0L) > now) return@forEach

            durabilityCooldowns[target.key] = now + DURABILITY_COOLDOWN_MS
            enqueue(
                key = "durability:${target.key}",
                type = NotificationType.DURABILITY_WARN,
                detail = "${stack.hoverName.string} 低耐久: $remaining",
                stack = stack.copy(),
                skinTexture = null,
                sound = SoundEvents.ANVIL_LAND,
                volume = 0.6f,
                pitch = 1.2f,
                alertSound = ANSoundManager.AlertSound.DurabilityWarn
            )
        }
    }

    private fun detectModuleToggles() {
        if (!ANServiceRegistry.isInitialized) return
        val modules = ANServiceRegistry.runtime.moduleManager.allModules()
        if (moduleStates.isEmpty()) {
            snapshotModuleStates(modules)
            return
        }

        modules.forEach { module ->
            if (module === this) {
                moduleStates[module.name] = module.enabled
                return@forEach
            }

            val previous = moduleStates.put(module.name, module.enabled)
            if (previous == null || previous == module.enabled) return@forEach

            enqueue(
                key = "module:${module.name}:${module.enabled}:${System.currentTimeMillis()}",
                type = NotificationType.MODULE_TOGGLE,
                detail = "${displayModuleName(module)} ${if (module.enabled) "Enable" else "Disable"}",
                stack = ItemStack.EMPTY,
                skinTexture = null,
                sound = if (module.enabled) SoundEvents.EXPERIENCE_ORB_PICKUP else SoundEvents.ANVIL_LAND,
                volume = 1f,
                pitch = if (module.enabled) 1.2f else 0.8f,
                alertSound = if (module.enabled) ANSoundManager.AlertSound.ModuleEnable else ANSoundManager.AlertSound.ModuleDisable
            )
        }
    }

    private fun snapshotModuleStates(modules: List<ANModule> = if (ANServiceRegistry.isInitialized) ANServiceRegistry.runtime.moduleManager.allModules() else emptyList()) {
        moduleStates.clear()
        modules.forEach { moduleStates[it.name] = it.enabled }
    }

    private fun displayModuleName(module: ANModule): String {
        return (module as? ANBaseModule)?.getDisplayHudName() ?: module.name
    }

    private fun durabilityTargets(player: Player): List<DurabilityTarget> {
        val targets = mutableListOf<DurabilityTarget>()
        val equipment = listOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND
        )
        equipment.forEach { slot ->
            targets += DurabilityTarget("equipment:${slot.name}", player.getItemBySlot(slot))
        }
        return targets
    }

    private fun enqueue(
        key: String,
        type: NotificationType,
        detail: String,
        stack: ItemStack,
        skinTexture: Identifier?,
        sound: SoundEvent,
        volume: Float,
        pitch: Float,
        alertSound: ANSoundManager.AlertSound? = null
    ) {
        if (notifications.any { it.key == key }) return
        val player = Minecraft.getInstance().player ?: return
        notifications += Notification(key, type, detail, stack, skinTexture, System.currentTimeMillis())
        while (notifications.size > MAX_NOTIFICATIONS) notifications.removeAt(0)
        if (alertSound != null) {
            ANSoundManager.playAlert(alertSound, volume)
        } else {
            player.playSound(sound, volume, pitch)
        }
    }

    private fun renderNotifications(context: GuiGraphicsExtractor) {
        if (notifications.isEmpty()) return
        val now = System.currentTimeMillis()
        val baseY = (context.guiHeight() / 100f) * posY.value
        val iterator = notifications.iterator()
        var row = 0
        while (iterator.hasNext()) {
            val notification = iterator.next()
            val elapsed = now - notification.createdAt
            val lifetime = SLIDE_MS * 2L + (delay.value * 1000f).toLong()
            if (elapsed >= lifetime) {
                iterator.remove()
                continue
            }
            renderNotification(context, notification, baseY + row * (PLATE_HEIGHT + PLATE_GAP), elapsed, lifetime)
            row++
        }
    }

    private fun renderNotification(
        context: GuiGraphicsExtractor,
        notification: Notification,
        y: Float,
        elapsed: Long,
        lifetime: Long
    ) {
        val slideIn = (elapsed.toFloat() / SLIDE_MS).coerceIn(0f, 1f)
        val slideOut = ((lifetime - elapsed).toFloat() / SLIDE_MS).coerceIn(0f, 1f)
        val progress = minOf(slideIn, slideOut)
        val width = PLATE_WIDTH
        val x = context.guiWidth() - (width + 8f) * easeOut(progress)

        ANRender2DEngine.borderedRoundedRect(context, x, y, width, PLATE_HEIGHT, 8f, 1f, plateFill.value.getColor(), plateBorder.value.getColor())
        renderIcon(context, notification, x + 8f, y + 7f)

        val textX = x + 34f
        fontRenderer.draw(context, notification.type.title, textX, y + 6f, infoColor.value.getColor(), 0.8f)
        fontRenderer.draw(context, fitText(notification.detail), textX, y + 20f, textColor.value.getColor(), 0.8f)
    }

    private fun renderIcon(context: GuiGraphicsExtractor, notification: Notification, x: Float, y: Float) {
        when (notification.type) {
            NotificationType.PLAYER_ENTER -> notification.skinTexture?.let { texture ->
                drawHead(context, texture, x, y, ICON_SIZE)
            }
            NotificationType.DURABILITY_WARN -> if (!notification.stack.isEmpty) {
                drawItem(context, notification.stack, x, y, ICON_SIZE)
            }
            NotificationType.MODULE_TOGGLE -> drawModuleIcon(context, x, y, ICON_SIZE)
        }
    }

    private fun drawModuleIcon(context: GuiGraphicsExtractor, x: Float, y: Float, size: Float) {
        val iconX = x.roundToInt()
        val iconY = y.roundToInt()
        val iconSize = size.roundToInt()
        context.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, plateBorder.value.getColor())
        context.fill(iconX + 3, iconY + 3, iconX + iconSize - 3, iconY + iconSize - 3, infoColor.value.getColor())
    }

    private fun drawHead(context: GuiGraphicsExtractor, texture: Identifier, x: Float, y: Float, size: Float) {
        val drawSize = size.roundToInt()
        context.blit(texture, x.roundToInt(), y.roundToInt(), 8f, 8f, drawSize, drawSize, 64, 64)
        context.blit(texture, x.roundToInt(), y.roundToInt(), 40f, 8f, drawSize, drawSize, 64, 64)
    }

    private fun drawItem(context: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, size: Float) {
        context.pose().pushPose()
        val scale = size / 16f
        context.pose().translate(x.toDouble(), y.toDouble(), 0.0)
        context.pose().scale(scale, scale, 1.0f)
        context.renderItem(stack.copy(), 0, 0)
        context.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0)
        context.pose().popPose()
    }

    private fun fitText(text: String): String {
        return if (text.length <= 24) text else text.take(21) + "..."
    }

    private fun easeOut(value: Float): Float {
        return 1f - (1f - value) * (1f - value)
    }

    private data class Notification(
        val key: String,
        val type: NotificationType,
        val detail: String,
        val stack: ItemStack,
        val skinTexture: Identifier?,
        val createdAt: Long
    )

    private enum class NotificationType(val title: String) {
        PLAYER_ENTER("PlayerEnter"),
        DURABILITY_WARN("DurabilityWarn"),
        MODULE_TOGGLE("Module")
    }

    private data class DurabilityTarget(
        val key: String,
        val stack: ItemStack
    )

    private companion object {
        private const val PLATE_WIDTH = 120f
        private const val PLATE_HEIGHT = 34f
        private const val PLATE_GAP = 5f
        private const val ICON_SIZE = 18f
        private const val SLIDE_MS = 260L
        private const val MAX_NOTIFICATIONS = 5
        private const val DURABILITY_COOLDOWN_MS = 30_000L
        private const val DURABILITY_RESET_MS = 60_000L
    }
}
