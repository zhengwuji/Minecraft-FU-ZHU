package anpilot.client.features.manager.rotation

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

object RotationUtil {
    
    fun getRotationsTo(src: Vec3, dest: Vec3): FloatArray {
        val diff = dest.subtract(src)
        val yaw = (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0).toFloat()
        val pitch = Math.toDegrees(-Math.atan2(diff.y, Math.hypot(diff.x, diff.z))).toFloat()

        val player = Minecraft.getInstance().player ?: return floatArrayOf(0f, 0f)
        val playerYaw = player.yRot
        val playerPitch = player.xRot

        val yaw1 = playerYaw + Mth.wrapDegrees(yaw - playerYaw)
        val pitch1 = playerPitch + Mth.wrapDegrees(pitch - playerPitch)

        return floatArrayOf(yaw1, Mth.clamp(pitch1, -90.0f, 90.0f))
    }

    fun getRotationVector(yaw: Float, pitch: Float): Vec3 {
        val f = pitch * (Math.PI.toFloat() / 180.0f)
        val g = -yaw * (Math.PI.toFloat() / 180.0f)
        val h = Mth.cos(g)
        val i = Mth.sin(g)
        val j = Mth.cos(f)
        val k = Mth.sin(f)
        return Vec3((i * j).toDouble(), (-k).toDouble(), (h * j).toDouble())
    }
}
