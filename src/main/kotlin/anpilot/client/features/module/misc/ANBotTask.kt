package anpilot.client.features.module.misc

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANTickEvent
import anpilot.client.features.event.impl.PacketEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.utility.ANTimer
import anpilot.client.minecraft.mixin.accessor.ANMinecraftClientAccessor
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

class ANBotTask : ANBaseModule(
    name = "BotTask",
    description = "定时或在收到特定聊天指令时，对准星瞄准的目标执行左键或右键操作",
    category = ANModuleCategory.MISC,
    chineseName = "定时点击"
) {
    enum class TriggerMode(val label: String) {
        TIMER("定时模式"),
        MESSAGE("消息触发")
    }

    enum class ClickType(val label: String) {
        RIGHT("右键"),
        LEFT("左键")
    }

    val triggerMode = addSetting(ANSetting("Mode", TriggerMode.TIMER))
    val clickType = addSetting(ANSetting("ClickType", ClickType.RIGHT))

    val player = addSetting(ANSetting("Player", "PlayerName") { triggerMode.value == TriggerMode.MESSAGE })
    val text = addSetting(ANSetting("Message", "Text") { triggerMode.value == TriggerMode.MESSAGE })

    val cooldownTime = addSetting(ANSetting("Timer", 5, 1, 60) { triggerMode.value == TriggerMode.TIMER })

    private val timer = ANTimer()

    @Volatile
    private var triggerOnce = false

    override fun onEnable() {
        timer.reset()
        triggerOnce = false
    }

    @ANEventHandler
    fun onTick(event: ANTickEvent) {
        val mcPlayer = mc.player ?: return
        if (mc.level == null) return

        val currentClick = clickType.value

        if (triggerMode.value == TriggerMode.MESSAGE) {
            if (triggerOnce) {
                triggerOnce = false
                performClick(currentClick)
            }
        } else if (triggerMode.value == TriggerMode.TIMER) {
            val delayMs = (cooldownTime.value * 1000).toLong()
            if (timer.passedAndResetMs(delayMs)) {
                performClick(currentClick)
            }
        }
    }

    @ANEventHandler
    fun onPacketReceive(event: PacketEvent.Receive) {
        if (triggerMode.value != TriggerMode.MESSAGE) return

        val messageContent = when (val packet = event.packet) {
            is ClientboundSystemChatPacket -> packet.content().string
            is ClientboundPlayerChatPacket -> packet.unsignedContent()?.string ?: packet.body().content()
            else -> null
        } ?: return

        val targetPlayer = player.value.trim()
        val targetText = text.value.trim()

        val matchPlayer = targetPlayer.isEmpty() || targetPlayer.equals("null", ignoreCase = true) || messageContent.contains(targetPlayer, ignoreCase = true)
        val matchText = targetText.isEmpty() || targetText.equals("null", ignoreCase = true) || messageContent.contains(targetText, ignoreCase = true)

        if (matchPlayer && matchText) {
            sendClientMessage("收到匹配消息，触发对准星目标的操作！")
            triggerOnce = true
        }
    }

    private fun performClick(type: ClickType) {
        val mcPlayer = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val hit = mc.hitResult ?: return

        when (type) {
            ClickType.LEFT -> {
                when (hit.type) {
                    HitResult.Type.ENTITY -> {
                        val entityHit = hit as EntityHitResult
                        gameMode.attack(mcPlayer, entityHit.entity)
                        mcPlayer.swing(InteractionHand.MAIN_HAND)
                    }
                    HitResult.Type.BLOCK -> {
                        val blockHit = hit as BlockHitResult
                        gameMode.startDestroyBlock(blockHit.blockPos, blockHit.direction)
                        mcPlayer.swing(InteractionHand.MAIN_HAND)
                    }
                    else -> {
                        (mc as? ANMinecraftClientAccessor)?.`anpilot$startAttack`()
                    }
                }
            }
            ClickType.RIGHT -> {
                when (hit.type) {
                    HitResult.Type.ENTITY -> {
                        val entityHit = hit as EntityHitResult
                        gameMode.interact(mcPlayer, entityHit.entity, InteractionHand.MAIN_HAND)
                        mcPlayer.swing(InteractionHand.MAIN_HAND)
                    }
                    HitResult.Type.BLOCK -> {
                        val blockHit = hit as BlockHitResult
                        gameMode.useItemOn(mcPlayer, InteractionHand.MAIN_HAND, blockHit)
                        mcPlayer.swing(InteractionHand.MAIN_HAND)
                    }
                    else -> {
                        gameMode.useItem(mcPlayer, InteractionHand.MAIN_HAND)
                        mcPlayer.swing(InteractionHand.MAIN_HAND)
                    }
                }
            }
        }
    }
}
