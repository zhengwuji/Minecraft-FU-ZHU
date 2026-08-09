package anpilot.client.features.ai.task.basefinder

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.FireworkUtils
import anpilot.client.features.module.misc.ANBaseFinder
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

class BaseFinderTask(agent: ANAgent, private val module: ANBaseFinder) : AITask(agent) {
    private var session: BaseFinderSession = BaseFinderSession()
    private var targets: List<BlockPos> = emptyList()
    private var stage = Stage.TAKEOFF
    private var tickCounter = 0
    private var lastFireworkAt = 0L
    private var lastCheckpointAt = 0L
    private val pendingChunks = ArrayDeque<ChunkPos>()
    private val queuedChunks = mutableSetOf<String>()
    private var sessionCleared = false
    private var landingStarted = false

    override fun start() {
        val player = player ?: return
        val loaded = if (module.resume.value) BaseFinderStore.loadSession() else null
        session = loaded ?: BaseFinderSession(player.blockPosition().x, player.blockPosition().y, player.blockPosition().z)
        targets = spiralTargets(session.center(), module.searchRadiusBlocks(), module.step.value.toInt())
        if (targets.isEmpty()) {
            AgentUtils.sendMessage("BaseFinder has no targets")
            finished = true
            return
        }
        session = session.copy(targetIndex = session.targetIndex.coerceIn(0, targets.lastIndex))
        BaseFinderStore.saveSession(session)
        AgentUtils.sendMessage("BaseFinder started at target ${session.targetIndex + 1}/${targets.size}")
    }

    override fun tick() {
        val player = player ?: run {
            finished = true
            return
        }
        if (ANAgent.minecraft.level == null) {
            finished = true
            return
        }
        tickCounter++

        if (player.getItemBySlot(EquipmentSlot.CHEST).item != Items.ELYTRA) {
            AgentUtils.sendMessage("BaseFinder stopped: no elytra equipped")
            finished = true
            return
        }
        if (fireworkCount() < module.minFireworks.value.toInt()) {
            AgentUtils.sendMessage("BaseFinder stopped: fireworks below ${module.minFireworks.value.toInt()}")
            finished = true
            return
        }

        collectLoadedChunks()
        scanPendingChunks()
        flyTick()
        checkpointIfNeeded()

        if (session.targetIndex >= targets.size) {
            startLanding()
            return
        }
    }

    private fun startLanding() {
        if (landingStarted) return
        landingStarted = true
        val landingTarget = landingTarget()
        if (landingTarget == null) {
            AgentUtils.sendMessage("BaseFinder 扫描完成，但没有找到可降落位置")
            BaseFinderStore.clearSession()
            sessionCleared = true
            finished = true
            return
        }
        BaseFinderStore.clearSession()
        sessionCleared = true
        agent.scheduler.push(BaseFinderLandingTask(agent, module, landingTarget))
        finished = true
    }

    override fun stop() {
        agent.movement.stop()
        if (!sessionCleared) BaseFinderStore.saveSession(session)
    }

    private fun flyTick() {
        val player = player ?: return
        val target = targets.getOrNull(session.targetIndex) ?: return
        val yaw = yawTo(target)

        when (stage) {
            Stage.TAKEOFF -> {
                agent.rotation.request(AgentUtils.lerpYaw(yaw, 0.15f), AgentUtils.lerpPitch(-25f, 0.2f))
                if (player.isFallFlying) {
                    stage = Stage.ASCEND
                    boostIfReady(force = true)
                    return
                }
                if (player.onGround()) {
                    agent.movement.jump()
                } else if (tickCounter % 5 == 0) {
                    ANAgent.minecraft.connection?.send(ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                }
                if (tickCounter > 100) {
                    AgentUtils.sendMessage("BaseFinder takeoff timeout")
                    finished = true
                }
            }
            Stage.ASCEND -> {
                agent.rotation.request(AgentUtils.lerpYaw(yaw, 0.12f), AgentUtils.lerpPitch(-25f, 0.15f))
                boostIfReady()
                if (player.y >= module.altitude.value) stage = Stage.CRUISE
            }
            Stage.CRUISE -> {
                val pitch = cruisePitch()
                agent.rotation.request(AgentUtils.lerpYaw(yaw, 0.12f), AgentUtils.lerpPitch(pitch, 0.12f))
                if (pitch < -10f) boostIfReady()
                if (horizontalDistance(target) <= TARGET_REACHED_DISTANCE) {
                    session = session.copy(targetIndex = session.targetIndex + 1)
                    BaseFinderStore.saveSession(session)
                    stage = Stage.CRUISE
                }
                if (!player.isFallFlying && !player.onGround()) {
                    ANAgent.minecraft.connection?.send(ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                }
            }
        }
    }

    private fun cruisePitch(): Float {
        val player = player ?: return -2f
        val obstacleY = forwardObstacleY()
        if (obstacleY != null && obstacleY + OBSTACLE_MARGIN > player.y) return -35f
        if (player.y < module.altitude.value) return -22f
        if (player.y > module.altitude.value + 45f) return 10f
        return -2f
    }

    private fun forwardObstacleY(): Int? {
        val level = ANAgent.minecraft.level ?: return null
        val player = player ?: return null
        val yawRad = Math.toRadians(player.yRot.toDouble())
        var highest = Int.MIN_VALUE
        for (distance in OBSTACLE_START..OBSTACLE_END step OBSTACLE_STEP) {
            val x = (player.x - sin(yawRad) * distance).toInt()
            val z = (player.z + cos(yawRad) * distance).toInt()
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            if (y > highest) highest = y
        }
        return highest.takeIf { it != Int.MIN_VALUE }
    }

    private fun boostIfReady(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if ((force || now - lastFireworkAt >= FIREWORK_INTERVAL_MS) && FireworkUtils.useFirework()) {
            lastFireworkAt = now
        }
    }

    private fun collectLoadedChunks() {
        val level = ANAgent.minecraft.level ?: return
        val player = player ?: return
        val center = player.chunkPosition()
        val radius = module.chunkScanRadius.value.toInt()
        for (cx in center.x - radius..center.x + radius) {
            for (cz in center.z - radius..center.z + radius) {
                if (!level.hasChunk(cx, cz)) continue
                val key = chunkKey(cx, cz)
                if (key in session.scannedChunks || key in queuedChunks) continue
                pendingChunks += ChunkPos(cx, cz)
                queuedChunks += key
            }
        }
    }

    private fun scanPendingChunks() {
        val level = ANAgent.minecraft.level ?: return
        repeat(module.chunksPerTick.value.toInt()) {
            val chunkPos = pendingChunks.removeFirstOrNull() ?: return
            val key = chunkKey(chunkPos.x, chunkPos.z)
            queuedChunks.remove(key)
            if (key in session.scannedChunks) return@repeat
            val result = BaseFinderScanner.scan(level, chunkPos)
            session.scannedChunks += key
            handleScanResult(result)
        }
    }

    private fun handleScanResult(result: BaseFinderScanResult) {
        val reasons = mutableListOf<String>()
        reasons += thresholdReasons("containers", result.containers)
        reasons += thresholdReasons("blocks", result.blocks)
        if (reasons.isEmpty()) return

        val level = ANAgent.minecraft.level ?: return
        val dimension = level.dimension().location().toString()
        val detectionKey = "$dimension:${result.chunkPos.x}:${result.chunkPos.z}:${reasons.sorted().joinToString("+")}"
        if (detectionKey in session.detectionKeys) return
        session.detectionKeys += detectionKey
        val detectionPos = detectionPos(result)
        val detectedText = reasons.joinToString()
        val timeText = LocalDateTime.now().format(TIME_FORMAT)
        BaseFinderStore.appendDetection(
            BaseFinderDetection(
                Coordinate = "X: ${detectionPos.x} Y: ${detectionPos.y} Z: ${detectionPos.z}",
                Detected = detectedText,
                Time = timeText
            )
        )
        if (module.xaeroWaypoint.value) {
            BaseFinderXaeroWaypoints.append("ANBaseFinder-$timeText", detectionPos, dimension)
        }
        BaseFinderStore.saveSession(session)
        AgentUtils.sendMessage("BaseFinder 检测到疑似基地特征：$detectedText，坐标：${detectionPos.x}, ${detectionPos.y}, ${detectionPos.z}")
    }

    private fun thresholdReasons(category: String, values: Map<String, Int>): List<String> {
        return values.mapNotNull { (key, count) ->
            val threshold = module.thresholdFor(category, key)
            if (threshold > 0 && count >= threshold) "$category:$key=$count/$threshold" else null
        }
    }

    private fun checkpointIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCheckpointAt < module.checkpointSeconds.value.toLong() * 1000L) return
        lastCheckpointAt = now
        BaseFinderStore.saveSession(session)
    }

    private fun fireworkCount(): Int {
        val inventory = ANAgent.minecraft.player?.inventory ?: return 0
        var count = 0
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.item == Items.FIREWORK_ROCKET) count += stack.count
        }
        return count
    }

    private fun yawTo(pos: BlockPos): Float {
        val player = player ?: return 0f
        val dx = pos.x + 0.5 - player.x
        val dz = pos.z + 0.5 - player.z
        return (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
    }

    private fun horizontalDistance(pos: BlockPos): Double {
        val player = player ?: return Double.MAX_VALUE
        val dx = pos.x + 0.5 - player.x
        val dz = pos.z + 0.5 - player.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun chunkKey(x: Int, z: Int): String = "$x,$z"

    private fun landingTarget(): BlockPos? {
        val level = ANAgent.minecraft.level ?: return null
        val base = targets.lastOrNull() ?: player?.blockPosition() ?: return null
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, base.x, base.z)
        return BlockPos(base.x, y + 1, base.z)
    }

    private fun detectionPos(result: BaseFinderScanResult): BlockPos {
        val level = ANAgent.minecraft.level ?: return player?.blockPosition() ?: BlockPos.ZERO
        val x = result.chunkPos.minBlockX + 8
        val z = result.chunkPos.minBlockZ + 8
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
        return BlockPos(x, y, z)
    }

    private fun spiralTargets(center: BlockPos, radius: Int, step: Int): List<BlockPos> {
        val result = mutableListOf<BlockPos>()
        var x = center.x
        var z = center.z
        var dx = 1
        var dz = 0
        var segmentLength = 1
        var stepsTaken = 0
        var segmentPassed = 0
        val safeStep = step.coerceAtLeast(64)

        while (abs(x - center.x) <= radius && abs(z - center.z) <= radius && result.size < MAX_TARGETS) {
            x += dx * safeStep
            z += dz * safeStep
            stepsTaken++
            result += BlockPos(x, module.altitude.value.toInt(), z)

            if (stepsTaken >= segmentLength) {
                stepsTaken = 0
                segmentPassed++
                val nextDx = -dz
                dz = dx
                dx = nextDx
                if (segmentPassed % 2 == 0) segmentLength++
            }
        }
        return result
    }

    private enum class Stage { TAKEOFF, ASCEND, CRUISE }

    private companion object {
        private const val FIREWORK_INTERVAL_MS = 4_000L
        private const val TARGET_REACHED_DISTANCE = 32.0
        private const val OBSTACLE_START = 20
        private const val OBSTACLE_END = 56
        private const val OBSTACLE_STEP = 12
        private const val OBSTACLE_MARGIN = 12.0
        private const val MAX_TARGETS = 50_000
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
