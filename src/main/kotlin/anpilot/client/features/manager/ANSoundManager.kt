package anpilot.client.features.manager

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.slf4j.LoggerFactory
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl

object ANSoundManager {
    private const val DEFAULT_VOLUME = 1.0f
    private const val DEFAULT_PITCH = 1.0f

    private val logger = LoggerFactory.getLogger("ANSoundManager")
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val soundsFolder: File get() = ANConfigManager.customSoundsFolder()

    enum class AlertSound(
        val externalFileName: String,
        val fallback: SoundEvent,
        val fallbackPitch: Float = DEFAULT_PITCH
    ) {
        ChatMention("mention", SoundEvents.PLAYER_LEVELUP),
        PrivateMessage("private", SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f),
        PlayerEnter("player_enter", SoundEvents.EXPERIENCE_ORB_PICKUP),
        DurabilityWarn("durability_warn", SoundEvents.ANVIL_LAND, 1.2f),
        ModuleEnable("module_enable", SoundEvents.EXPERIENCE_ORB_PICKUP),
        ModuleDisable("module_disable", SoundEvents.ANVIL_LAND)
    }

    fun initialize() {
        soundsFolder.mkdirs()
    }

    fun playAlert(sound: AlertSound, volume: Float = DEFAULT_VOLUME) {
        if (!playCustom(sound.externalFileName, volume, sound.fallback, sound.fallbackPitch)) {
            playBuiltin(sound.fallback, volume, sound.fallbackPitch)
        }
    }

    fun playBuiltin(sound: SoundEvent, volume: Float = DEFAULT_VOLUME, pitch: Float = DEFAULT_PITCH) {
        val player = mc.player ?: return
        player.playSound(sound, volume.coerceIn(0.0f, 1.0f), pitch)
    }

    fun playCustom(
        name: String,
        volume: Float = DEFAULT_VOLUME,
        fallback: SoundEvent? = null,
        fallbackPitch: Float = DEFAULT_PITCH
    ): Boolean {
        val file = soundFile(name)
        if (!file.isFile) return false

        Thread({
            try {
                AudioSystem.getAudioInputStream(file.absoluteFile).use { stream ->
                    val clip = AudioSystem.getClip()
                    clip.open(stream)
                    applyVolume(clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl, volume)
                    clip.addLineListener { event ->
                        if (event.type == javax.sound.sampled.LineEvent.Type.STOP) {
                            clip.close()
                        }
                    }
                    clip.start()
                }
            } catch (exception: Exception) {
                logger.warn("Failed to play sound {}", file.absolutePath, exception)
                mc.execute {
                    if (fallback != null) playBuiltin(fallback, volume, fallbackPitch)
                    mc.gui.chat.addMessage(Component.literal("[SoundManager] 无法播放声音: ${file.absolutePath}"))
                }
            }
        }, "ANSoundManager-${file.nameWithoutExtension}").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun soundFile(name: String): File {
        initialize()
        val cleanName = name.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".wav")
        return File(soundsFolder, "$cleanName.wav")
    }

    private fun applyVolume(control: FloatControl?, volume: Float) {
        if (control == null) return
        val clamped = volume.coerceIn(0.0f, 1.0f)
        val value = control.minimum + (control.maximum - control.minimum) * clamped
        control.value = value.coerceIn(control.minimum, control.maximum)
    }
}
