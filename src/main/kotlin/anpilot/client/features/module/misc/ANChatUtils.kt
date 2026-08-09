package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.manager.ANSoundManager
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.minecraft.duck.ANGuiMessageLineExt
import com.mojang.authlib.GameProfile
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import anpilot.client.compat.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.client.GuiMessage
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.world.entity.player.Player
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.lang.StringBuilder
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.components.ComponentRenderUtils
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth

class ANChatUtils : ANBaseModule(
    name = "ChatUtils",
    description = "增强游戏聊天体验，支持时间戳、聊天后缀、防坐标泄露与私聊提示音",
    category = ANModuleCategory.MISC,
    chineseName = "聊天工具"
) {
    val prefix = addSetting(ANSetting("Prefix", ""))
    val suffix = addSetting(ANSetting("Suffix", ""))
    val timeStamp = addSetting(ANSetting("Time", true))
    val avatarMark = addSetting(ANSetting("Head", true))
    val mentionSound = addSetting(ANSetting("MentionSound", true))
    val privateSound = addSetting(ANSetting("WhisperSound", true))
    val antiCoordLeak = addSetting(ANSetting("AntiCoords", true))
    val encodeMessage = addSetting(ANSetting("Encode", true))
    val antiSpam = addSetting(ANSetting("AntiSpam", true))

    init {
        INSTANCE = this
    }

    override fun onDisable() {
        lastMentionSound = 0L
        lastPrivateSound = 0L
    }

    fun handleAntiSpam(component: Component, trimmedMessages: MutableList<GuiMessage.Line>): Component {
        if (!enabled || !antiSpam.value) return component
        if (trimmedMessages.isEmpty()) return component

        val chatWidthOption = mc.options.chatWidth().get()
        val chatScaleOption = mc.options.chatScale().get()
        val maxTextLength = Mth.floor(ChatComponent.getWidth(chatWidthOption) / chatScaleOption)
        val newLines = ComponentRenderUtils
            .wrapComponents(component, maxTextLength, mc.font)

        var spamCounter = 1
        var matchingLines = 0

        fun getFormattedAsString(text: FormattedCharSequence): String {
            val sb = StringBuilder()
            text.accept { _, _, cp ->
                sb.appendCodePoint(cp)
                true
            }
            return sb.toString()
        }

        fun getLineAsString(visible: GuiMessage.Line): String = getFormattedAsString(visible.content())

        for (i in trimmedMessages.indices.reversed()) {
            val oldLine = getLineAsString(trimmedMessages[i])

            if (matchingLines <= newLines.size - 1) {
                val newLine = getFormattedAsString(newLines[matchingLines])

                if (matchingLines < newLines.size - 1) {
                    if (oldLine == newLine) {
                        matchingLines++
                    } else {
                        matchingLines = 0
                    }
                    continue
                }

                if (!oldLine.startsWith(newLine)) {
                    matchingLines = 0
                    continue
                }

                if (i > 0 && matchingLines == newLines.size - 1) {
                    val nextOldLine = getLineAsString(trimmedMessages[i - 1])
                    val twoLines = oldLine + nextOldLine
                    val addedText = twoLines.substring(newLine.length)

                    if (addedText.startsWith(" [x") && addedText.endsWith("]")) {
                        val oldSpamCounter = addedText.substring(3, addedText.length - 1)
                        val counterVal = oldSpamCounter.toIntOrNull()
                        if (counterVal != null) {
                            spamCounter += counterVal
                            matchingLines++
                            continue
                        }
                    }
                }

                if (oldLine.length == newLine.length) {
                    spamCounter++
                } else {
                    val addedText = oldLine.substring(newLine.length)
                    if (!addedText.startsWith(" [x") || !addedText.endsWith("]")) {
                        matchingLines = 0
                        continue
                    }

                    val oldSpamCounter = addedText.substring(3, addedText.length - 1)
                    val counterVal = oldSpamCounter.toIntOrNull()
                    if (counterVal == null) {
                        matchingLines = 0
                        continue
                    }

                    spamCounter += counterVal
                }
            }

            for (i2 in (i + matchingLines) downTo i) {
                trimmedMessages.removeAt(i2)
            }
            matchingLines = 0
        }

        if (spamCounter > 1) {
            val oldText = component as? MutableComponent ?: component.copy()
            val newText = MutableComponent.create(oldText.contents)
            newText.style = oldText.style
            oldText.siblings.forEach(newText::append)
            return newText.append(" [x$spamCounter]")
        }
        return component
    }

    private fun shouldBlockOutgoing(message: String): Boolean {
        if (!enabled || !antiCoordLeak.value || isCommand(message)) return false
        if (!containsCoordinates(message)) return false

        sendClientMessage("消息疑似包含坐标，已取消发送")
        return true
    }

    private fun transformOutgoing(message: String): String {
        if (!enabled || isCommand(message) || message.startsWith(MARKER)) return message

        val plain = buildString {
            append(prefix.value)
            append(message)
            append(suffix.value)
        }
        return if (encodeMessage.value) MARKER + encode(plain) else plain
    }

    private fun decorateSystemMessage(component: Component): Component {
        if (!enabled) return component
        val text = component.string
        val decoded = decodeMessage(text)
        playAlerts(decoded.plain, null)
        
        val out = Component.literal("")
        if (timeStamp.value) out.append(timeComponent())
        if (avatarMark.value && findSenderProfile(decoded.display) != null) {
            out.append(Component.literal(HEAD_SPACER))
        }
        if (text.contains(MARKER)) {
            out.append(decoded.displayComponent())
        } else {
            out.append(component)
        }
        return out
    }

    private fun decorateDisguisedMessage(component: Component): Component {
        if (!enabled) return component
        val text = component.string
        val decoded = decodeMessage(text)
        playAlerts(decoded.plain, null)
        return if (text.contains(MARKER)) {
            decoded.displayComponent()
        } else {
            component
        }
    }

    private fun decoratePlayerMessage(message: PlayerChatMessage): PlayerChatMessage {
        if (!enabled) return message
        val text = message.unsignedContent()?.string ?: message.signedContent()
        val decoded = decodeMessage(text)
        val senderName = Minecraft.getInstance().connection
            ?.getPlayerInfo(message.sender())
            ?.profile
            ?.name
        playAlerts(decoded.plain, senderName)

        return if (text.contains(MARKER)) {
            message.withUnsignedContent(decoded.displayComponent())
        } else {
            message
        }
    }

    private fun decoratePlayerDisplayComponent(component: Component): Component {
        if (!enabled) return component
        val out = Component.literal("")
        if (timeStamp.value) out.append(timeComponent())
        if (avatarMark.value) out.append(Component.literal(HEAD_SPACER))
        out.append(component)
        return out
    }

    private fun timeComponent(): Component {
        return Component.literal("[${TIME_FORMAT.format(LocalTime.now())}] ")
            .withStyle(ChatFormatting.BLUE)
    }

    private fun playAlerts(text: String, senderName: String?) {
        val player = Minecraft.getInstance().player ?: return
        if (senderName != null && senderName == player.name.string) return

        val now = System.currentTimeMillis()
        val lower = text.lowercase()
        val ownName = player.name.string

        if (mentionSound.value && ownName.isNotBlank() && text.contains(ownName, ignoreCase = true) && now - lastMentionSound >= SOUND_COOLDOWN_MS) {
            ANSoundManager.playAlert(ANSoundManager.AlertSound.ChatMention)
            lastMentionSound = now
        }

        if (privateSound.value && isPrivateMessage(lower, ownName.lowercase()) && now - lastPrivateSound >= SOUND_COOLDOWN_MS) {
            ANSoundManager.playAlert(ANSoundManager.AlertSound.PrivateMessage)
            lastPrivateSound = now
        }
    }

    private fun isPrivateMessage(lowerText: String, ownName: String): Boolean {
        return lowerText.contains("whisper") ||
            lowerText.contains(" whispers ") ||
            lowerText.contains("msg") ||
            lowerText.contains("tell") ||
            lowerText.contains("-> me") ||
            lowerText.contains("-> $ownName") ||
            lowerText.contains("$ownName ->") ||
            lowerText.contains("私聊") ||
            lowerText.contains("悄悄")
    }

    private fun containsCoordinates(message: String): Boolean {
        return XYZ_COORD_PATTERN.containsMatchIn(message) || PLAIN_COORD_PATTERN.containsMatchIn(message)
    }

    private fun isCommand(message: String): Boolean {
        return message.startsWith("/")
    }

    private fun encode(text: String): String {
        val base = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        val out = StringBuilder(base.length + base.length / 5)
        base.forEachIndexed { index, c ->
            val pos = BASE64_URL.indexOf(c)
            out.append(if (pos >= 0) BASE64_URL[(pos * 17 + 23) and 63] else c)
            if (index % 5 == 4 && index != base.lastIndex) out.append('.')
        }
        return out.toString()
    }

    private fun decodeMessage(text: String): DecodedMessage {
        val markerIndex = text.indexOf(MARKER)
        if (markerIndex < 0) return DecodedMessage(text, text)

        val encodedSection = text.substring(markerIndex + MARKER.length)
        val encoded = encodedSection
            .filter { it != '.' && BASE64_URL.indexOf(it) >= 0 }
        if (encoded.isEmpty()) return DecodedMessage(text, text)

        val base = StringBuilder(encoded.length)
        encoded.forEach { c ->
            val pos = BASE64_URL.indexOf(c)
            base.append(BASE64_URL[((pos - 23) * 49) and 63])
        }

        val prefix = text.substring(0, markerIndex)
        val decodedBody = runCatching {
            String(Base64.getUrlDecoder().decode(base.toString()), StandardCharsets.UTF_8)
        }.getOrNull()

        if (decodedBody == null) return DecodedMessage(text, text)

        val rawEncoded = MARKER + encodedSection.takeWhile { it == '.' || BASE64_URL.indexOf(it) >= 0 }
        val plain = prefix + decodedBody
        val display = if (INSTANCE?.encodeMessage?.value == true) {
            "$plain  $rawEncoded"
        } else {
            plain
        }
        return DecodedMessage(plain, display, rawEncoded.takeIf { INSTANCE?.encodeMessage?.value == true })
    }

    private data class DecodedMessage(
        val plain: String,
        val display: String,
        val rawEncoded: String? = null
    ) {
        fun displayComponent(): Component {
            val raw = rawEncoded ?: return Component.literal(display)
            return Component.literal("")
                .append(Component.literal(plain).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal("  "))
                .append(Component.literal(raw))
        }
    }

    companion object {
        private const val MARKER = "[ANPILOT][ENCODE]"
        private const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private const val SOUND_COOLDOWN_MS = 1000L
        private const val CHAT_HEAD_OFFSET = 10
        private const val HEAD_SPACER = "   "
        private val chatSender = ThreadLocal<GameProfile?>()
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val CHAT_NAME_PATTERN = Regex("""^(?:\[\d{2}:\d{2}]\s*)?<([^>]{1,16})>""")
        private val PLAIN_COORD_PATTERN =
            Regex("""(?<![\w-])-?\d{2,7}(?:\.\d+)?[, ]+-?\d{1,4}(?:\.\d+)?[, ]+-?\d{2,7}(?:\.\d+)?(?![\w-])""")
        private val XYZ_COORD_PATTERN =
            Regex("""(?i)x\s*[:= ]\s*-?\d{1,7}(?:\.\d+)?.*y\s*[:= ]\s*-?\d{1,4}(?:\.\d+)?.*z\s*[:= ]\s*-?\d{1,7}(?:\.\d+)?""")

        @JvmStatic
        var INSTANCE: ANChatUtils? = null
            private set

        private var lastMentionSound = 0L
        private var lastPrivateSound = 0L

        @JvmStatic
        fun beginChatSender(sender: GameProfile?) {
            chatSender.set(sender)
        }

        @JvmStatic
        fun endChatSender() {
            chatSender.remove()
        }

        @JvmStatic
        fun currentChatSender(): GameProfile? = chatSender.get()

        @JvmStatic
        fun shouldRenderPlayerHeads(): Boolean {
            val module = INSTANCE ?: return false
            return module.enabled && module.avatarMark.value
        }

        @JvmStatic
        fun findSenderProfile(text: String): GameProfile? {
            val minecraft = Minecraft.getInstance()
            val connection = minecraft.connection ?: return null
            val info = findPlayerInfoForChat(null, text)
            if (info != null) return info.profile
            val player = findWorldPlayerForChat(null, text) ?: return null
            return connection.getPlayerInfo(player.uuid)?.profile ?: GameProfile(player.uuid, player.name.string)
        }

        @JvmStatic
        fun chatHeadOffset(): Int = CHAT_HEAD_OFFSET

        @JvmStatic
        fun playerHeadX(): Int {
            val module = INSTANCE ?: return 0
            return if (module.timeStamp.value) {
                Minecraft.getInstance().font.width("[${TIME_FORMAT.format(LocalTime.now())}] ")
            } else {
                0
            }
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        @JvmStatic
        fun renderPlayerHead(graphics: GuiGraphicsExtractor, line: GuiMessage.Line, y: Int, opacity: Float) {
            if (!shouldRenderPlayerHeads()) return
            val lineExt = (line as Any) as? ANGuiMessageLineExt ?: return
            if (!lineExt.`anpilot$isStartOfEntry`()) return

            val connection = Minecraft.getInstance().connection ?: return
            val sender = lineExt.`anpilot$getSender`()
            val content = getLineAsString(line)
            val playerInfo = resolvePlayerInfo(sender, content) ?: return
            val texture = playerInfo.skinLocation

            val x = playerHeadX()
            graphics.blit(texture, x, y, 8f, 8f, 8, 8, 64, 64)
            graphics.blit(texture, x, y, 40f, 8f, 8, 8, 64, 64)
        }

        private fun getFormattedAsString(text: FormattedCharSequence): String {
            val sb = StringBuilder()
            text.accept { _, _, cp ->
                sb.appendCodePoint(cp)
                true
            }
            return sb.toString()
        }

        fun getLineAsString(visible: GuiMessage.Line): String = getFormattedAsString(visible.content())

        private fun resolvePlayerInfo(sender: GameProfile?, text: String): PlayerInfo? {
            val connection = Minecraft.getInstance().connection ?: return null
            if (sender != null) {
                connection.getPlayerInfo(sender.id)?.let { return it }
                findPlayerInfoByName(sender.name)?.let { return it }
            }
            findPlayerInfoForChat(sender?.name, text)?.let { return it }
            val player = findWorldPlayerForChat(sender?.name, text) ?: return null
            return connection.getPlayerInfo(player.uuid)
        }

        private fun findPlayerInfoForChat(senderName: String?, text: String): PlayerInfo? {
            senderName?.takeIf { it.isNotBlank() }?.let { name ->
                findPlayerInfoByName(name)?.let { return it }
            }

            val cleanText = text.replace(Regex("""^\[\d{2}:\d{2}]\s*"""), "")
            val prefixPart = cleanText.take(80)
            val candidates = linkedSetOf<String>()
            CHAT_NAME_PATTERN.find(cleanText)?.groupValues?.getOrNull(1)?.let(candidates::add)
            Regex("""[A-Za-z0-9_]{3,16}""").findAll(prefixPart).forEach { candidates.add(it.value) }

            for (candidate in candidates) {
                findPlayerInfoByName(candidate)?.let { return it }
            }
            return null
        }

        private fun findPlayerInfoByName(name: String): PlayerInfo? {
            val connection = Minecraft.getInstance().connection ?: return null
            return connection.onlinePlayers.firstOrNull {
                it.profile.name.equals(name, ignoreCase = true)
            }
        }

        private fun findWorldPlayerForChat(senderName: String?, text: String): Player? {
            val players = Minecraft.getInstance().level?.players() ?: return null
            val cleanText = text.replace(Regex("""^\[\d{2}:\d{2}]\s*"""), "")
            val prefixPart = cleanText.take(80)
            val candidates = linkedSetOf<String>()

            senderName?.takeIf { it.isNotBlank() }?.let(candidates::add)
            CHAT_NAME_PATTERN.find(cleanText)?.groupValues?.getOrNull(1)?.let(candidates::add)
            Regex("""[A-Za-z0-9_]{3,16}""").findAll(prefixPart).forEach { candidates.add(it.value) }

            for (candidate in candidates) {
                players.firstOrNull { it.name.string.equals(candidate, ignoreCase = true) }?.let { return it }
            }

            return players.firstOrNull { player ->
                Regex("""(?<![A-Za-z0-9_])${Regex.escape(player.name.string)}(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(prefixPart)
            }
        }

        @JvmStatic
        fun shouldBlockOutgoingChat(message: String): Boolean {
            return INSTANCE?.shouldBlockOutgoing(message) == true
        }

        @JvmStatic
        fun transformOutgoingChat(message: String): String {
            return INSTANCE?.transformOutgoing(message) ?: message
        }

        @JvmStatic
        fun decorateSystemChat(component: Component): Component {
            return INSTANCE?.decorateSystemMessage(component) ?: component
        }

        @JvmStatic
        fun decorateDisguisedChat(component: Component): Component {
            return INSTANCE?.decorateDisguisedMessage(component) ?: component
        }

        @JvmStatic
        fun decoratePlayerChat(message: PlayerChatMessage): PlayerChatMessage {
            return INSTANCE?.decoratePlayerMessage(message) ?: message
        }

        @JvmStatic
        fun decoratePlayerDisplay(component: Component): Component {
            return INSTANCE?.decoratePlayerDisplayComponent(component) ?: component
        }
    }
}
