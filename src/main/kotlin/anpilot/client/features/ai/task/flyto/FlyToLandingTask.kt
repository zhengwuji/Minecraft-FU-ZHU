package anpilot.client.features.ai.task.flyto

import anpilot.client.features.ai.agent.ANAgent
import anpilot.client.features.ai.task.AITask
import anpilot.client.features.ai.utils.AgentUtils
import anpilot.client.features.ai.utils.FireworkUtils
import anpilot.client.features.module.misc.ANFlyTo
import net.minecraft.core.BlockPos
import kotlin.math.atan2
import kotlin.math.sqrt

class FlyToLandingTask(agent: ANAgent) : AITask(agent) {
    private var landingPos: BlockPos? = null
    private var lowRecoverBoosted = false

    override fun start() {
        val module = agent.module as? ANFlyTo ?: return
        landingPos = findLandingPos(module)
        if (landingPos == null) {
            module.noLandingFound()
            finished = true
        } else {
            AgentUtils.sendMessage("FlyTo landing at ${landingPos!!.x}, ${landingPos!!.y}, ${landingPos!!.z}")
        }
    }

    override fun tick() {
        val player = player ?: run {
            finished = true
            return
        }
        val module = agent.module as? ANFlyTo ?: run {
            finished = true
            return
        }
        if (ANAgent.minecraft.level == null) {
            finished = true
            return
        }

        val target = landingPos ?: run {
            finished = true
            return
        }

        if (player.onGround()) {
            module.complete("Arrived")
            finished = true
            return
        }

        flyTowardLanding(module, target)
    }

    private fun flyTowardLanding(module: ANFlyTo, target: BlockPos) {
        val player = player ?: return
        val distance = horizontalDistance(target)
        val pitch = when {
            distance <= 4.0 && hasClearVerticalPathTo(target.y) -> 75f
            distance <= 12.0 -> 45f
            else -> approachPitch(module, target)
        }

        agent.rotation.request(AgentUtils.lerpYaw(yawTo(target), 0.25f), AgentUtils.lerpPitch(pitch, 0.25f))

        if (!lowRecoverBoosted && distance > 20.0 && player.y < target.y + 12.0 && pitch < 0f) {
            FireworkUtils.useFirework()
            lowRecoverBoosted = true
        }
    }

    private fun findLandingPos(module: ANFlyTo): BlockPos? {
        val player = player ?: return null
        val base = player.blockPosition()
        findSafeFeetPos(base.x, base.z)?.let { return it }

        for (radius in 1..ANFlyTo.LANDING_SEARCH) {
            for (xOffset in -radius..radius) {
                findSafeFeetPos(base.x + xOffset, base.z + radius)?.let { return it }
                findSafeFeetPos(base.x + xOffset, base.z - radius)?.let { return it }
            }
            for (zOffset in (-radius + 1)..(radius - 1)) {
                findSafeFeetPos(base.x + radius, base.z + zOffset)?.let { return it }
                findSafeFeetPos(base.x - radius, base.z + zOffset)?.let { return it }
            }
        }
        return null
    }

    private fun findSafeFeetPos(x: Int, z: Int): BlockPos? {
        val level = ANAgent.minecraft.level ?: return null
        val player = player ?: return null
        val topY = minOf(player.blockY - 1, level.maxBuildHeight - 1)
        for (y in topY downTo level.minBuildHeight) {
            val ground = BlockPos(x, y, z)
            if (!isSolid(ground)) continue
            val feet = ground.above()
            if (isAir(feet) && isAir(feet.above())) return feet
        }
        return null
    }

    private fun isSolid(pos: BlockPos): Boolean {
        val level = ANAgent.minecraft.level ?: return false
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }

    private fun isAir(pos: BlockPos): Boolean {
        val level = ANAgent.minecraft.level ?: return false
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }

    private fun hasClearVerticalPathTo(y: Int): Boolean {
        val level = ANAgent.minecraft.level ?: return false
        val player = player ?: return false
        val base = player.blockPosition()
        for (checkY in (y + 1)..base.y) {
            val pos = BlockPos(base.x, checkY, base.z)
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        }
        return true
    }

    private fun approachPitch(module: ANFlyTo, target: BlockPos): Float {
        val player = player ?: return 0f
        val dx = target.x + 0.5 - player.x
        val dz = target.z + 0.5 - player.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val desiredY = target.y + (module.highGlideY.value - target.y) *
            (horizontalDist.toFloat() / 600f).coerceIn(0f, 1f)
        val error = player.y.toFloat() - desiredY
        return (error * 0.6f + 8f).coerceIn(-10f, 35f)
    }

    private fun horizontalDistance(pos: BlockPos): Double {
        val player = player ?: return 0.0
        val dx = pos.x + 0.5 - player.x
        val dz = pos.z + 0.5 - player.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun yawTo(pos: BlockPos): Float {
        val player = player ?: return 0f
        val dx = pos.x + 0.5 - player.x
        val dz = pos.z + 0.5 - player.z
        return (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
    }
}
