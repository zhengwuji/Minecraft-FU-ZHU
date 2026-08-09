package anpilot.client.compat

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3

fun GameRenderer.projectPointToScreen(worldPos: Vec3): Vec3 {
    val mc = Minecraft.getInstance()
    val camera = mc.gameRenderer.mainCamera
    val cameraPos = camera.position
    val relPos = worldPos.subtract(cameraPos)

    val yaw = Math.toRadians(camera.yRot.toDouble())
    val pitch = Math.toRadians(camera.xRot.toDouble())

    val cosYaw = Math.cos(-yaw)
    val sinYaw = Math.sin(-yaw)
    val cosPitch = Math.cos(-pitch)
    val sinPitch = Math.sin(-pitch)

    val x1 = relPos.x * cosYaw - relPos.z * sinYaw
    val z1 = relPos.x * sinYaw + relPos.z * cosYaw
    val y2 = relPos.y * cosPitch - z1 * sinPitch
    val z2 = relPos.y * sinPitch + z1 * cosPitch

    if (z2 <= 0.1) {
        return Vec3(0.0, 0.0, -1.0)
    }

    val fov = Math.toRadians(mc.options.fov().get().toDouble())
    val halfFovTan = Math.tan(fov / 2.0)

    val screenX = (x1 / (z2 * halfFovTan * (mc.window.guiScaledWidth.toDouble() / mc.window.guiScaledHeight.toDouble())))
    val screenY = (y2 / (z2 * halfFovTan))

    return Vec3(screenX, screenY, z2)
}
