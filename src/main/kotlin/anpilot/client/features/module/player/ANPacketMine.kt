package anpilot.client.features.module.player

import anpilot.client.api.module.ANModuleCategory
import anpilot.client.features.event.ANEventHandler
import anpilot.client.features.event.impl.ANAttackBlockEvent
import anpilot.client.features.module.ANBaseModule
import anpilot.client.features.module.ANWorldRenderModule
import anpilot.client.features.setting.ANSetting
import anpilot.client.features.setting.impl.ColorGroupSetting
import anpilot.client.renderer.ANColor
import anpilot.client.renderer.render.ANRender3DEngine
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.awt.Color
import java.util.Timer
import java.util.TimerTask
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.enchantment.Enchantment

class ANPacketMine : ANBaseModule(
    name = "PacketMine",
    description = "极速静默挖掘方块，支持后台双挖",
    category = ANModuleCategory.PLAYER,
    chineseName = "方块包挖"
), ANWorldRenderModule {
    val mode = addSetting(ANSetting("Mode", MiningPacketMode.Grim))

    val range = addSetting(ANSetting("Range", 4.0f, 1.0f, 6.0f))
    val breakTiming = addSetting(ANSetting("BreakTiming", 0.8, 0.0, 2.0))

    val doubleMine = addSetting(ANSetting("DoubleMine", false))
    val remine = addSetting(ANSetting("Remine", RemineMode.Off))
    val limitInterval = addSetting(ANSetting("InstantDelay", 100, 25, 1000){remine.value != RemineMode.Off})
    val instantLimit = addSetting(ANSetting("Limit", 10, 1, 20) { mode.value != MiningPacketMode.Grim && remine.value != RemineMode.Off })

    val grimMaxBreaks = addSetting(ANSetting("BreakLimit", 6, 0, 10) { mode.value == MiningPacketMode.Grim })
    val grimBypassGround = addSetting(ANSetting("AntiInAir", false) { mode.value == MiningPacketMode.Grim })
    val grimMineDelay = addSetting(ANSetting("MineDelay", 300, 0, 1000) { mode.value == MiningPacketMode.Grim })
    val grimPacketDelay = addSetting(ANSetting("PacketDelay", 0, 0, 1000) { mode.value == MiningPacketMode.Grim })

    val autoSwitch = addSetting(ANSetting("Switch", MineSwitchMode.SILENT))
    val usingPause = addSetting(ANSetting("UsingPause", true))
    val disableRange = addSetting(ANSetting("RangeDisable", true) { mode.value != MiningPacketMode.Grim })
    val grimSwing = addSetting(ANSetting("SwingHand", true))

    val fill = addSetting(ANSetting("Fill", true))
    val outlineColor = addSetting(ANSetting("OutlineColor", ColorGroupSetting(Color(53, 250, 31, 100).rgb)))
    val firstColor = addSetting(ANSetting("FirstColor", ColorGroupSetting(Color(53, 250, 31, 100).rgb)))
    val secondColor = addSetting(ANSetting("SecondColor", ColorGroupSetting(Color(88, 94, 255, 100).rgb)))


    
    private var manualMining = false
    private var mainMiningBlock: MiningData? = null
    private var packetMiningBlock: MiningData? = null
    private var pendingClear: MiningData? = null
    private var mainState: MiningRenderState? = null
    private var packetState: MiningRenderState? = null
    private var instantStartMs = 0L
    private var instantCount = 0

    
    private var grimTargetPos: BlockPos? = null
    private var grimSecondPos: BlockPos? = null
    private var grimTargetDirection = Direction.UP
    private var grimSecondDirection = Direction.UP
    private var grimStarted = false
    private var grimSecondStarted = false
    private var grimCompleted = false
    private var grimProgress = 0.0
    private var grimSecondProgress = 0.0
    private var grimPublicProgress = 0
    private var grimSecondPublicProgress = 0
    private var grimLastTime = System.currentTimeMillis()
    private var grimSecondLastTime = System.currentTimeMillis()
    private var grimOldSlot = -1
    private var grimHasSwitch = false
    private var grimSecondHasSwitch = false
    private var grimMaxBreaksCount = 0
    private var grimMineTimer = grimElapsedTimer()
    private var grimSwitchTimer = grimElapsedTimer()
    private var grimInstantTimer = grimElapsedTimer()
    private var grimSequence = 1
    private var grimProgressFactorCached = 0.0
    private var grimSecondProgressFactorCached = 0.0

    override fun onEnable() {
        if (mode.value == MiningPacketMode.Grim) {
            grimResetState()
        }
    }

    override fun onDisable() {
        clearMain()
        clearPacket()
        pendingClear = null
        instantStartMs = 0L
        instantCount = 0
        manualMining = false

        grimRestoreSwitch()
        grimResetState()
    }

    override fun onTick() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val level = minecraft.level
        if (player == null || level == null) {
            onDisable()
            return
        }

        if (mode.value == MiningPacketMode.Grim) {
            grimOnTick()
            return
        }

        tickPacket()
        tickMain()
        if (manualMining && mainMiningBlock == null) manualMining = false
    }

    @ANEventHandler
    fun onAttackBlock(event: ANAttackBlockEvent) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (player.isCreative) return

        val pos = event.blockPos
        val direction = event.direction

        if (mode.value == MiningPacketMode.Grim) {
            if (player.isSpectator) return
            if (!grimCanBreak(level.getBlockState(pos), pos)) return
            event.setCancelled(true)
            if (!grimPassed(grimMineTimer, grimMineDelay.value.toLong())) return
            grimMine(pos, direction)
            return
        }

        val state = level.getBlockState(pos)
        event.setCancelled(true)
        if (isMining(pos) || !canMineBlock(state)) return
        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) > range.value * range.value) return

        manualMining = true
        startMining(pos, direction)
        if (grimSwing.value) {
            player.swing(InteractionHand.MAIN_HAND, false)
        } else {
            sendPacket(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        }
    }

    override fun renderWorld(context: LevelRenderContext) {
        if (mode.value == MiningPacketMode.Grim) {
            grimRenderWorld(context)
            return
        }
        renderState(context, mainState, breakTiming.value.toFloat(), firstColor.value.toANColor())
        renderState(context, packetState, 1.0f, secondColor.value.toANColor())
    }

    fun startMining(blockPos: BlockPos, direction: Direction) {
        if (mode.value == MiningPacketMode.Grim) {
            grimMine(blockPos, direction)
            return
        }

        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        var oldDamage = -1.0f

        if (doubleMine.value) {
            pendingClear?.let { pending ->
                if (pending == mainMiningBlock && pending.blockDamage < breakTiming.value.toFloat()) {
                    clearMain()
                    if (pending.blockPos == blockPos) oldDamage = pending.blockDamage
                }
                pendingClear = null
            }

            val main = mainMiningBlock
            if (main != null && !main.isBlockMined()) {
                val packet = packetMiningBlock
                if (packet == null || packet.isBlockMined()) {
                    packetMiningBlock = main.copy(1.0f)
                    packetState = MiningRenderState(packetMiningBlock!!, Animation(true))
                }
            }
        }

        val bestSlot = getBestTool(blockPos)
        val stack = if (bestSlot == -1) player.mainHandItem.copy() else player.inventory.getItem(bestSlot).copy()
        mainMiningBlock = MiningData(blockPos, direction, breakTiming.value.toFloat(), stack, player)
        if (oldDamage > 0.0f) mainMiningBlock?.blockDamage = oldDamage
        mainState = MiningRenderState(mainMiningBlock!!, Animation(true))
        mode.value.sendStartPackets(blockPos, direction, doubleMine.value)
        mainMiningBlock?.started = true
    }

    private fun tickMain() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val main = mainMiningBlock ?: return

        if (main.squaredDistanceTo() > range.value * range.value) {
            if (disableRange.value) clearMain()
            return
        }

        if (!main.started && !main.isAir() && remine.value == RemineMode.Fast) {
            mode.value.sendStartPackets(main.blockPos, main.direction, doubleMine.value)
            main.started = true
        }

        if (usingPause.value && player.isUsingItem) {
            clearMain()
            return
        }

        val damage = main.tickDelta()
        if (damage < breakTiming.value.toFloat()) return

        if (main.isAir()) {
            main.resetTicksMining()
            if (manualMining) manualMining = false
            if (remine.value != RemineMode.Instant) {
                main.blockDamage = 0.0f
                main.lastDamage = 0.0f
                main.started = false
                return
            }
            if (!tryConsumeInstantBudget()) return
        } else if (main.hasMinedFor(30)) {
            clearMain()
            return
        }

        val bestTool = getBestTool(main.blockPos)
        if (autoSwitch.value == MineSwitchMode.SILENT) {
            sendWithSwap(bestTool) {
                mode.value.sendStopPackets(main.blockPos, main.direction)
            }
        } else if (autoSwitch.value == MineSwitchMode.DELAY) {
            val oldSlot = player.inventory.selected
            switchToSlot(bestTool)
            mode.value.sendStopPackets(main.blockPos, main.direction)
            if (oldSlot != -1 && oldSlot != bestTool) {
                switchToSlot(oldSlot)
            }
        } else {
            mode.value.sendStopPackets(main.blockPos, main.direction)
        }
        instantCount++

        if (remine.value == RemineMode.Off) clearMain()
    }

    private fun tickPacket() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val packet = packetMiningBlock ?: return

        if (packet.squaredDistanceTo() > range.value * range.value) {
            if (disableRange.value) clearPacket()
            return
        }

        if (usingPause.value && player.isUsingItem) {
            clearPacket()
            return
        }

        val damage = packet.tickDelta()
        if (damage < breakTiming.value.toFloat()) return

        val bestTool = getBestTool(packet.blockPos)
        if (autoSwitch.value == MineSwitchMode.SILENT) {
            sendWithSwap(bestTool) {
                mode.value.sendStopPackets(packet.blockPos, packet.direction)
            }
        } else if (autoSwitch.value == MineSwitchMode.DELAY) {
            val oldSlot = player.inventory.selected
            switchToSlot(bestTool)
            mode.value.sendStopPackets(packet.blockPos, packet.direction)
            if (oldSlot != -1 && oldSlot != bestTool) {
                switchToSlot(oldSlot)
            }
        } else {
            mode.value.sendStopPackets(packet.blockPos, packet.direction)
        }

        val main = mainMiningBlock
        if (main != null && main.blockDamage < breakTiming.value.toFloat()) pendingClear = main
        clearPacket()
    }

    private fun tryConsumeInstantBudget(): Boolean {
        if (remine.value != RemineMode.Instant) return true
        val now = System.currentTimeMillis()
        val windowMs = max(limitInterval.value, 25)
        if (instantStartMs == 0L || now - instantStartMs >= windowMs) {
            instantStartMs = now
            instantCount = 0
        }
        return instantCount < max(instantLimit.value, 1)
    }

    private fun clearMain() {
        mainMiningBlock?.let { if (!it.isDoneMining()) it.abort() }
        mainMiningBlock = null
        mainState?.animation?.state = false
    }

    private fun clearPacket() {
        packetMiningBlock = null
        packetState?.animation?.state = false
    }

    fun isMining(blockPos: BlockPos): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            grimTargetPos == blockPos || grimSecondPos == blockPos
        } else {
            mainMiningBlock?.blockPos == blockPos || packetMiningBlock?.blockPos == blockPos
        }
    }

    fun hasFreeMine(): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            grimTargetPos == null || (doubleMine.value && grimSecondPos == null)
        } else {
            mainMiningBlock == null || packetMiningBlock == null
        }
    }

    fun isManualMining(): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            grimTargetPos != null
        } else {
            manualMining
        }
    }

    fun getMainMiningPos(): BlockPos? {
        return if (mode.value == MiningPacketMode.Grim) {
            grimTargetPos
        } else {
            mainMiningBlock?.blockPos
        }
    }

    fun getPacketMiningPos(): BlockPos? {
        return if (mode.value == MiningPacketMode.Grim) {
            grimSecondPos
        } else {
            packetMiningBlock?.blockPos
        }
    }

    fun getPendingClearPos(): BlockPos? {
        return if (mode.value == MiningPacketMode.Grim) {
            null
        } else {
            pendingClear?.blockPos
        }
    }

    fun isMainDoneMining(): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            grimCompleted
        } else {
            mainMiningBlock?.isDoneMining() ?: true
        }
    }

    fun isMainBlockMined(): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            val pos = grimTargetPos ?: return true
            grimCompleted && grimIsAir(pos)
        } else {
            mainMiningBlock?.isBlockMined() ?: true
        }
    }

    fun hasMainMinedFor(ticks: Int): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            grimMaxBreaksCount >= ticks
        } else {
            mainMiningBlock?.hasMinedFor(ticks) ?: false
        }
    }

    fun isPacketBlockMined(): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            val pos = grimSecondPos ?: return true
            val done = grimSecondProgress >= grimMineTicks(pos, grimBestTool(pos)) * breakTiming.value
            done && grimIsAir(pos)
        } else {
            packetMiningBlock?.isBlockMined() ?: true
        }
    }

    fun hasPacketMinedFor(ticks: Int): Boolean {
        return if (mode.value == MiningPacketMode.Grim) {
            false
        } else {
            packetMiningBlock?.hasMinedFor(ticks) ?: false
        }
    }

    fun getMiningRange(): Float {
        return range.value
    }

    private fun renderState(context: LevelRenderContext, state: MiningRenderState?, miningSpeed: Float, color: ANColor) {
        val renderState = state ?: return
        renderState.updateFrameTime()
        renderState.animation.update()
        if (renderState.animation.factor < 0.01f && !renderState.animation.state) {
            if (renderState === mainState) mainState = null
            if (renderState === packetState) packetState = null
            return
        }
        renderState.data.render(context, miningSpeed, color, renderState.frameDelta)
    }

    private fun getBestTool(pos: BlockPos): Int {
        val player = Minecraft.getInstance().player ?: return -1
        val level = Minecraft.getInstance().level ?: return -1
        val state = level.getBlockState(pos)
        var bestSlot = -1
        var bestSpeed = 1.0f
        for (slot in 0 until 9) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val destroySpeed = stack.getDestroySpeed(state)
            val efficiency = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack)
            val score = destroySpeed + efficiency
            if (score > bestSpeed) {
                bestSpeed = score
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun sendWithSwap(slot: Int, action: () -> Unit) {
        val player = Minecraft.getInstance().player ?: return action()
        if (slot == -1 || slot == player.inventory.selected) {
            action()
            return
        }
        val oldSlot = player.inventory.selected
        sendPacket(ServerboundSetCarriedItemPacket(slot))
        action()
        sendPacket(ServerboundSetCarriedItemPacket(oldSlot))
    }

    private fun switchToSlot(slot: Int) {
        val player = Minecraft.getInstance().player ?: return
        if (slot !in 0 until 9) return
        player.inventory.selected = slot
        sendPacket(ServerboundSetCarriedItemPacket(slot))
    }

    private fun canMineBlock(state: BlockState): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        return state.getDestroySpeed(level, BlockPos.ZERO) != -1.0f && !state.isAir && state.fluidState.isEmpty
    }

    private fun sendPacket(packet: Packet<*>) {
        Minecraft.getInstance().connection?.send(packet)
    }

    private fun grimSendPacket(packet: Packet<*>) {
        sendPacket(packet)
    }

    private fun ColorGroupSetting.toANColor(): ANColor = ANColor.fromArgb(getColor())

    private fun Float.smoothStep(): Float = (this * this * (3f - 2f * this)).coerceIn(0f, 1f)

    
    
    

    private fun grimOnTick() {
        val player = Minecraft.getInstance().player ?: return grimResetState()
        if (Minecraft.getInstance().level == null || player.isCreative || player.isSpectator) return grimResetState()

        if (grimHasSwitch && grimPassed(grimSwitchTimer, 100L)) {
            grimRestoreSwitch()
        }
        if (grimMaxBreaks.value > 0 && grimMaxBreaksCount >= grimMaxBreaks.value * 10) {
            grimClearTarget()
        }

        grimTickSecond()
        grimTickTarget()

        grimProgressFactorCached = grimProgressFactor(grimTargetPos, grimProgress)
        grimSecondProgressFactorCached = grimProgressFactor(grimSecondPos, grimSecondProgress)
    }

    private fun grimRenderWorld(context: LevelRenderContext) {
        grimRenderMine(context, grimTargetPos, grimProgressFactorCached, firstColor.value.toANColor())
        grimRenderMine(context, grimSecondPos, grimSecondProgressFactorCached, secondColor.value.toANColor())
    }

    fun grimMine(pos: BlockPos, direction: Direction = Direction.UP) {
        if (!grimPassed(grimMineTimer, grimMineDelay.value.toLong())) return
        grimMineTimer = System.currentTimeMillis()
        grimMaxBreaksCount = 0

        if (doubleMine.value) {
            val target = grimTargetPos
            if (target != null && grimSecondPos == null && target != pos) {
                if (grimCompleted) {
                    if (grimMineDelay.value > 0) {
                        grimClearTarget()
                        return
                    }
                    grimSetTarget(pos, direction)
                } else {
                    grimSecondPos = target
                    grimSecondDirection = grimTargetDirection
                    grimTargetPos = pos
                    grimTargetDirection = direction
                    grimSecondStarted = false
                    grimSecondProgress = 0.0
                    grimSecondPublicProgress = 0
                    grimStarted = false
                }
            } else if (target == null || target != pos) {
                grimSetTarget(pos, direction)
            }
        } else if (grimTargetPos != pos) {
            grimSetTarget(pos, direction)
        }
    }

    private fun grimTickTarget() {
        val pos = grimTargetPos ?: return
        val player = Minecraft.getInstance().player ?: return
        val level = Minecraft.getInstance().level ?: return

        if (player.eyePosition.distanceTo(Vec3.atCenterOf(pos)) > range.value) {
            grimClearTarget()
            return
        }

        grimPublicProgress = grimProgressPercent(pos, grimProgress)
        if (grimProgress >= grimMineTicks(pos, grimBestTool(pos)) * breakTiming.value && grimCompleted) {
            if (grimIsAir(pos)) {
                grimMaxBreaksCount = 0
            } else if (!grimIsPauseActive()) {
                grimMaxBreaksCount++
            }
        }

        if (remine.value == RemineMode.Instant && grimCompleted) {
            if (!grimIsAir(pos) && grimPassed(grimInstantTimer, limitInterval.value.toLong())) {
                grimSendStop(pos, grimTargetDirection, true)
                grimInstantTimer = System.currentTimeMillis()
            }
            return
        }

        val now = System.currentTimeMillis()
        val delta = (now - grimLastTime) / 1000.0
        grimLastTime = now

        if (!grimStarted) {
            grimSendStart(pos, grimTargetDirection, primary = true)
            return
        }

        grimProgress += delta * if (player.onGround()) 20.0 else 4.0
        if (grimProgress >= grimMineTicks(pos, grimBestTool(pos)) * breakTiming.value) {
            grimSendStop(pos, grimTargetDirection, true)
            grimCompleted = true
            if (remine.value != RemineMode.Instant && grimSecondPos == null) {
                grimTargetPos = null
            }
        }
        if (!grimCanBreak(level.getBlockState(pos), pos) && remine.value != RemineMode.Instant) {
            grimClearTarget()
        }
    }

    private fun grimTickSecond() {
        val pos = grimSecondPos ?: return
        val player = Minecraft.getInstance().player ?: return

        if (!doubleMine.value) {
            grimSecondPos = null
            return
        }
        if (player.eyePosition.distanceTo(Vec3.atCenterOf(pos)) > range.value) {
            grimSecondPos = null
            return
        }

        grimMaybeSwitchForSecond()
        grimSecondPublicProgress = grimProgressPercent(pos, grimSecondProgress)
        val now = System.currentTimeMillis()
        val delta = (now - grimSecondLastTime) / 1000.0
        grimSecondLastTime = now

        if (!grimSecondStarted) {
            grimSendStart(pos, grimSecondDirection, primary = false)
            return
        }

        grimSecondProgress += delta * if (player.onGround()) 20.0 else 4.0
        if (grimSecondProgress >= grimMineTicks(pos, grimBestTool(pos)) * breakTiming.value) {
            grimSendStop(pos, grimSecondDirection, false)
            grimSecondPos = null
        }
    }

    private fun grimSendStart(pos: BlockPos, direction: Direction, primary: Boolean) {
        grimSendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, grimClickSide(pos, direction))
        
        val player = Minecraft.getInstance().player ?: return
        val bypassPos = BlockPos.containing(player.x, 321.0, player.z)
        grimSendAction(
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
            bypassPos,
            Direction.DOWN,
            sequenced = true
        )

        if (doubleMine.value) {
            val stopPos = pos.immutable()
            val stopDirection = grimClickSide(pos, direction)
            Timer("ANPacketMinePlusDelay", true).schedule(object : TimerTask() {
                override fun run() {
                    Minecraft.getInstance().execute {
                        grimSendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, stopPos, stopDirection)
                    }
                }
            }, grimPacketDelay.value.toLong())
        }
        grimSwingHand()
        if (primary) {
            grimStarted = true
            grimProgress = 0.0
            grimLastTime = System.currentTimeMillis()
        } else {
            grimSecondStarted = true
            grimSecondProgress = 0.0
            grimSecondLastTime = System.currentTimeMillis()
        }
    }

    private fun grimSendStop(pos: BlockPos, direction: Direction, primary: Boolean) {
        if (primary && grimIsPauseActive()) return
        if (primary && (!doubleMine.value || grimSecondPos == null)) {
            grimSwitchToBestTool(pos)
        }
        if (!primary) {
            grimSwitchToBestTool(pos, second = true)
        }
        if (grimBypassGround.value) {
            grimSendGroundBypass(pos)
        }
        grimSwingHand()
        grimSendAction(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
            pos,
            grimClickSide(pos, direction),
            sequenced = true
        )
    }

    private fun grimMaybeSwitchForSecond() {
        if (grimHasSwitch || grimSecondPos == null) return
        if (grimIsPauseActive()) return
        if (grimPublicProgress < 95 && grimSecondPublicProgress < 95) return
        grimSwitchToBestTool(grimSecondPos ?: return, second = true)
    }

    private fun grimSwitchToBestTool(pos: BlockPos, second: Boolean = false) {
        val player = Minecraft.getInstance().player ?: return
        val slot = grimBestTool(pos)
        if (slot == -1 || autoSwitch.value == MineSwitchMode.NONE) return
        if (!grimHasSwitch && !grimSecondHasSwitch) grimOldSlot = player.inventory.selected
        when (autoSwitch.value) {
            MineSwitchMode.NONE -> Unit
            MineSwitchMode.DELAY -> {
                player.inventory.selected = slot
                grimSendPacket(ServerboundSetCarriedItemPacket(slot))
            }

            MineSwitchMode.SILENT -> grimSendPacket(ServerboundSetCarriedItemPacket(slot))
        }
        grimSwitchTimer = System.currentTimeMillis()
        if (second) grimSecondHasSwitch = true else grimHasSwitch = true
    }

    private fun grimRestoreSwitch() {
        val player = Minecraft.getInstance().player ?: return
        val slot = grimOldSlot
        if (slot !in 0 until 9) {
            grimHasSwitch = false
            grimSecondHasSwitch = false
            return
        }
        when (autoSwitch.value) {
            MineSwitchMode.NONE -> Unit
            MineSwitchMode.DELAY -> {
                player.inventory.selected = slot
                grimSendPacket(ServerboundSetCarriedItemPacket(slot))
            }

            MineSwitchMode.SILENT -> grimSendPacket(ServerboundSetCarriedItemPacket(slot))
        }
        grimHasSwitch = false
        grimSecondHasSwitch = false
        grimOldSlot = -1
    }

    private fun grimSendGroundBypass(pos: BlockPos) {
        val player = Minecraft.getInstance().player ?: return
        if (player.isFallFlying || player.onGround() || grimIsAir(pos)) return
        grimSendPacket(
            ServerboundMovePlayerPacket.PosRot(
                player.x,
                player.y + 1.0e-9,
                player.z,
                player.yRot,
                player.xRot,
                true
            )
        )
        player.resetFallDistance()
    }

    private fun grimSendAction(
        action: ServerboundPlayerActionPacket.Action,
        pos: BlockPos,
        direction: Direction,
        sequenced: Boolean = false
    ) {
        val packet = if (sequenced) {
            ServerboundPlayerActionPacket(action, pos, direction, grimNextSequence())
        } else {
            ServerboundPlayerActionPacket(action, pos, direction)
        }
        grimSendPacket(packet)
    }

    private fun grimNextSequence(): Int {
        grimSequence += 1
        if (grimSequence > 1_000_000) grimSequence = 1
        return grimSequence
    }

    private fun grimSetTarget(pos: BlockPos, direction: Direction) {
        grimTargetPos = pos
        grimTargetDirection = direction
        grimPublicProgress = 0
        grimProgress = 0.0
        grimStarted = false
        grimCompleted = false
        grimLastTime = System.currentTimeMillis()
    }

    private fun grimClearTarget() {
        grimTargetPos = null
        grimStarted = false
        grimCompleted = false
        grimProgress = 0.0
        grimPublicProgress = 0
    }

    private fun grimResetState() {
        grimTargetPos = null
        grimSecondPos = null
        grimTargetDirection = Direction.UP
        grimSecondDirection = Direction.UP
        grimStarted = false
        grimSecondStarted = false
        grimCompleted = false
        grimProgress = 0.0
        grimSecondProgress = 0.0
        grimPublicProgress = 0
        grimSecondPublicProgress = 0
        grimMaxBreaksCount = 0
        grimOldSlot = -1
        grimHasSwitch = false
        grimSecondHasSwitch = false
        val elapsed = grimElapsedTimer()
        grimMineTimer = elapsed
        grimSwitchTimer = elapsed
        grimInstantTimer = elapsed
        grimLastTime = System.currentTimeMillis()
        grimSecondLastTime = grimLastTime
        grimProgressFactorCached = 0.0
        grimSecondProgressFactorCached = 0.0
    }

    private fun grimElapsedTimer(): Long = System.currentTimeMillis() - 999_999L

    private fun grimPassed(time: Long, delayMs: Long): Boolean =
        System.currentTimeMillis() - time >= delayMs

    private fun grimIsPauseActive(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        if (!usingPause.value || !Minecraft.getInstance().options.keyUse.isDown) return false
        return player.usedItemHand == InteractionHand.MAIN_HAND
    }

    private fun grimBestTool(pos: BlockPos): Int {
        val player = Minecraft.getInstance().player ?: return -1
        val level = Minecraft.getInstance().level ?: return -1
        val state = level.getBlockState(pos)
        var bestSlot = -1
        var bestScore = 1.0f
        for (slot in 0 until 9) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val efficiency = grimEnchantmentLevel(stack, Enchantments.BLOCK_EFFICIENCY).toFloat()
            val speed = stack.getDestroySpeed(state)
            val score = speed + efficiency
            if (score > bestScore) {
                bestScore = score
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun grimMineTicks(pos: BlockPos?, slot: Int): Double {
        if (pos == null) return 20.0
        val level = Minecraft.getInstance().level ?: return 20.0
        val player = Minecraft.getInstance().player ?: return 20.0
        val state = level.getBlockState(pos)
        val hardness = state.getDestroySpeed(level, pos)
        if (hardness < 0f) return Double.MAX_VALUE
        if (hardness == 0f) return 1.0
        val stack = if (slot == -1) ItemStack.EMPTY else player.inventory.getItem(slot)
        var speed = stack.getDestroySpeed(state)
        val efficiency = grimEnchantmentLevel(stack, Enchantments.BLOCK_EFFICIENCY)
        if (efficiency > 0 && speed > 1.0f) speed += (efficiency * efficiency + 1).toFloat()
        player.getEffect(MobEffects.DIG_SPEED)?.let { speed *= 1.0f + (it.amplifier + 1) * 0.2f }
        player.getEffect(MobEffects.DIG_SLOWDOWN)?.let {
            speed *= when (it.amplifier) {
                0 -> 0.3f
                1 -> 0.09f
                2 -> 0.0027f
                else -> 0.00081f
            }
        }
        val canHarvest = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)
        val damage = speed / hardness / if (canHarvest) 30f else 100f
        return if (damage <= 0f) Double.MAX_VALUE else 1.0 / damage
    }

    private fun grimProgressPercent(pos: BlockPos?, current: Double): Int {
        val maxTicks = grimMineTicks(pos, grimBestTool(pos ?: return 0)) * breakTiming.value
        if (maxTicks <= 0.0 || maxTicks == Double.MAX_VALUE) return 0
        return ((current / maxTicks) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun grimProgressFactor(pos: BlockPos?, current: Double): Double {
        val maxTicks = grimMineTicks(pos, grimBestTool(pos ?: return 0.0)) * breakTiming.value
        if (maxTicks <= 0.0 || maxTicks == Double.MAX_VALUE) return 0.0
        val raw = (current / maxTicks).coerceIn(0.0, 1.0)
        return raw
    }

    private fun grimRenderMine(context: LevelRenderContext, pos: BlockPos?, factor: Double, color: ANColor) {
        if (pos == null) return
        val level = Minecraft.getInstance().level ?: return
        val state = level.getBlockState(pos)
        val shape = state.getShape(level, pos, CollisionContext.empty())
        val bounds = if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
        
        val scale = factor.coerceIn(0.0, 1.0)
        val center = bounds.center
        val scaled = AABB.ofSize(center, bounds.xsize * scale, bounds.ysize * scale, bounds.zsize * scale)
        
        ANRender3DEngine.box(context, scaled, outlineColor.value.toANColor(), if (fill.value) color else null, alwaysPass = true)
    }

    private fun grimClickSide(pos: BlockPos, fallback: Direction): Direction {
        val level = Minecraft.getInstance().level ?: return fallback
        for (direction in Direction.entries) {
            val neighbor = pos.relative(direction.opposite)
            val state = level.getBlockState(neighbor)
            if (!state.isAir && !state.canBeReplaced()) return direction
        }
        return fallback
    }

    private fun grimIsAir(pos: BlockPos): Boolean {
        val level = Minecraft.getInstance().level ?: return true
        val state = level.getBlockState(pos)
        return state.isAir || state.canBeReplaced() || state.`is`(Blocks.FIRE)
    }

    private fun grimCanBreak(state: BlockState, pos: BlockPos): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        return !state.isAir && !state.canBeReplaced() && state.fluidState.isEmpty && state.getDestroySpeed(
            level,
            pos
        ) != -1.0f
    }

    private fun grimSwingHand() {
        val player = Minecraft.getInstance().player ?: return
        if (grimSwing.value) {
            player.swing(InteractionHand.MAIN_HAND)
        } else {
            grimSendPacket(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        }
    }

    private fun grimEnchantmentLevel(
        stack: ItemStack,
        enchantment: net.minecraft.world.item.enchantment.Enchantment
    ): Int {
        if (stack.isEmpty) return 0
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack)
    }

    
    
    

    private inner class MiningData(
        val blockPos: BlockPos,
        val direction: Direction,
        val maxProgress: Float,
        val miningStack: ItemStack,
        val player: LocalPlayer,
        var started: Boolean = false,
        var blockDamage: Float = 0f,
        var lastDamage: Float = 0f,
        var ticksMining: Int = 0,
        var visualProgress: Float = 0f
    ) {
        fun abort() {
            sendPacket(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction))
        }

        fun tickDelta(isMultitasking: Boolean = false): Float {
            lastDamage = blockDamage
            if (isDoneMining()) {
                if (!isMultitasking) ticksMining++
                return blockDamage
            }
            blockDamage += getBlockBreakingDelta()
            return blockDamage
        }

        fun resetTicksMining() {
            ticksMining = 0
        }

        fun isDoneMining(): Boolean = blockDamage >= maxProgress

        fun isBlockMined(): Boolean = isDoneMining() && isAir()

        fun isAir(): Boolean {
            val level = Minecraft.getInstance().level ?: return true
            return !canMineBlock(level.getBlockState(blockPos))
        }

        fun hasMinedFor(ticks: Int): Boolean = ticksMining >= ticks

        fun squaredDistanceTo(): Double = player.distanceToSqr(Vec3.atCenterOf(blockPos))

        fun copy(maxProgress: Float): MiningData {
            return MiningData(blockPos, direction, maxProgress, miningStack.copy(), player, false, blockDamage, lastDamage, ticksMining, visualProgress)
        }

        fun render(context: LevelRenderContext, miningSpeed: Float, color: ANColor, frameDelta: Float) {
            val level = Minecraft.getInstance().level ?: return
            val state = level.getBlockState(blockPos)
            val shape = state.getShape(level, blockPos, CollisionContext.empty())
            val bounds = if (shape.isEmpty) AABB(blockPos) else shape.bounds().move(blockPos)
            val scale = if (isDoneMining()) 1.0 else getVisualScale(miningSpeed, frameDelta).toDouble()
            val center = bounds.center
            val scaled = AABB.ofSize(center, bounds.xsize * scale, bounds.ysize * scale, bounds.zsize * scale)
            ANRender3DEngine.box(context, scaled, outlineColor.value.toANColor(), if (fill.value) color else null, alwaysPass = true)
        }

        private fun getVisualScale(maxProgress: Float, frameDelta: Float): Float {
            val target = (blockDamage / max(0.001f, maxProgress)).coerceIn(0f, 1f)
            if (isDoneMining()) {
                visualProgress = target
                return 1f
            }
            val smoothing = 1f - exp(-frameDelta * 14f)
            visualProgress += (target - visualProgress) * smoothing.coerceIn(0.05f, 1f)
            return visualProgress.coerceIn(0f, 1f).smoothStep()
        }

        private fun getBlockBreakingDelta(): Float {
            val level = Minecraft.getInstance().level ?: return 0f
            val state = level.getBlockState(blockPos)
            val hardness = state.getDestroySpeed(level, blockPos)
            if (hardness == -1.0f) return 0f
            val divisor = if (!state.requiresCorrectToolForDrops() || miningStack.isCorrectToolForDrops(state)) 30f else 100f
            return getBlockBreakingSpeed(player, miningStack, state) / hardness / divisor
        }
    }

    private class MiningRenderState(val data: MiningData, val animation: Animation) {
        private var lastFrameTime = System.currentTimeMillis()
        var frameDelta: Float = 0f
            private set

        fun updateFrameTime() {
            val now = System.currentTimeMillis()
            frameDelta = ((now - lastFrameTime).toFloat() / 1000f).coerceIn(0f, 0.1f)
            lastFrameTime = now
        }
    }

    private class Animation(var state: Boolean, private val lengthMs: Long = 300L) {
        private var factorInternal = if (state) 0f else 1f
        private var lastUpdate = System.currentTimeMillis()
        val factor: Float get() = factorInternal

        fun update() {
            val now = System.currentTimeMillis()
            val delta = ((now - lastUpdate).toFloat() / lengthMs).coerceAtLeast(0f)
            lastUpdate = now
            factorInternal = if (state) {
                min(1f, factorInternal + delta)
            } else {
                max(0f, factorInternal - delta)
            }
        }
    }

    private fun getBlockBreakingSpeed(player: LocalPlayer, stack: ItemStack, state: BlockState): Float {
        var speed = stack.getDestroySpeed(state)
        if (speed > 1.0f) {
            val efficiency = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack)
            if (efficiency > 0) speed += (efficiency * efficiency + 1).toFloat()
        }
        player.getEffect(MobEffects.DIG_SPEED)?.let { speed *= 1.0f + (it.amplifier + 1) * 0.2f }
        player.getEffect(MobEffects.DIG_SLOWDOWN)?.let {
            speed *= when (it.amplifier) {
                0 -> 0.3f
                1 -> 0.09f
                2 -> 0.0027f
                else -> 0.00081f
            }
        }
        if (!player.onGround()) speed /= 5.0f
        return speed.coerceAtLeast(0f)
    }

    enum class MiningPacketMode {
        Normal,
        Grim,
        GrimV3;

        fun sendStartPackets(blockPos: BlockPos, direction: Direction, doubleMine: Boolean = false) {
            val connection = Minecraft.getInstance().connection ?: return
            fun action(
                action: ServerboundPlayerActionPacket.Action,
                pos: BlockPos = blockPos,
                side: Direction = direction,
                sequenced: Boolean = false
            ) {
                val packet = if (sequenced) {
                    ServerboundPlayerActionPacket(action, pos, side, nextSequence())
                } else {
                    ServerboundPlayerActionPacket(action, pos, side)
                }
                connection.send(packet)
            }
            when (this) {
                Normal -> {
                    action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    action(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)
                    action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)
                    connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
                }
                Grim -> {
                    val side = clickSide(blockPos, direction)
                    action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side = side)
                    Minecraft.getInstance().player?.let { player ->
                        action(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            BlockPos.containing(player.x, 321.0, player.z),
                            Direction.DOWN,
                            sequenced = true
                        )
                    }
                    if (doubleMine) {
                        val stopPos = blockPos.immutable()
                        Timer("ANPacketMineGrimDelay", true).schedule(object : TimerTask() {
                            override fun run() {
                                Minecraft.getInstance().execute {
                                    connection.send(
                                        ServerboundPlayerActionPacket(
                                            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                                            stopPos,
                                            side
                                        )
                                    )
                                }
                            }
                        }, 0L)
                    }
                    connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
                }
                GrimV3 -> {
                    action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)
                    action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    action(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)
                    action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)
                    repeat(3) { connection.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND)) }
                }
            }
        }

        fun sendStopPackets(blockPos: BlockPos, direction: Direction) {
            val connection = Minecraft.getInstance().connection ?: return
            when (this) {
                Grim -> connection.send(
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        blockPos,
                        clickSide(blockPos, direction),
                        nextSequence()
                    )
                )
                else -> {
                    connection.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction))
                    if (this != Normal) {
                        connection.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction))
                    }
                }
            }
        }

        private fun clickSide(pos: BlockPos, fallback: Direction): Direction {
            val level = Minecraft.getInstance().level ?: return fallback
            for (direction in Direction.entries) {
                val neighbor = pos.relative(direction.opposite)
                val state = level.getBlockState(neighbor)
                if (!state.isAir && !state.canBeReplaced()) return direction
            }
            return fallback
        }

        private fun nextSequence(): Int {
            sequence += 1
            if (sequence > 1_000_000) sequence = 1
            return sequence
        }

        private companion object {
            var sequence = 1
        }
    }

    enum class RemineMode {
        Instant,
        Fast,
        Off
    }

    enum class MineSwitchMode {
        NONE,
        DELAY,
        SILENT
    }
}
