package anpilot.client.renderer.render

import anpilot.client.renderer.ANColor
import anpilot.client.compat.LevelRenderContext
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f

object ANRender3DEngine {
    fun line(context: LevelRenderContext, from: Vec3, to: Vec3, color: ANColor) {
        val start = toCameraSpace(context, from)
        val end = toCameraSpace(context, to)
        val normal = lineNormal(start, end)
        val pose = context.poseStack().last().pose()

        val consumer = context.bufferSource.getBuffer(RenderType.lines())
        consumer.vertex(pose, start.x.toFloat(), start.y.toFloat(), start.z.toFloat())
            .color(color.red, color.green, color.blue, color.alpha)
            .normal(normal.x, normal.y, normal.z)
            .endVertex()
        consumer.vertex(pose, end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
            .color(color.red, color.green, color.blue, color.alpha)
            .normal(normal.x, normal.y, normal.z)
            .endVertex()
    }

    fun box(context: LevelRenderContext, box: AABB, lineColor: ANColor, fillColor: ANColor? = null, alwaysPass: Boolean = false) {
        val min = toCameraSpace(context, Vec3(box.minX, box.minY, box.minZ))
        val max = toCameraSpace(context, Vec3(box.maxX, box.maxY, box.maxZ))
        fillColor?.takeIf { it.alpha > 0 }?.let { submitBoxFill(context, min.x, min.y, min.z, max.x, max.y, max.z, it) }
        submitBoxLines(context, min.x, min.y, min.z, max.x, max.y, max.z, lineColor, alwaysPass)
    }

    fun cube(context: LevelRenderContext, center: Vec3, size: Double, color: ANColor) {
        box(context, AABB.ofSize(center, size, size, size), color)
    }

    fun crosshairWorldPos(context: LevelRenderContext): Vec3 {
        return ANRender3DCenter.center ?: context.levelState().cameraRenderState.pos
    }

    private fun toCameraSpace(context: LevelRenderContext, pos: Vec3): Vec3 {
        return pos.subtract(context.levelState().cameraRenderState.pos)
    }

    private fun submitBoxLines(context: LevelRenderContext, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: ANColor, alwaysPass: Boolean = false) {
        val pose = context.poseStack().last().pose()
        val consumer = context.bufferSource.getBuffer(RenderType.lines())

        fun vertex(x: Double, y: Double, z: Double, normal: Vector3f) {
            consumer.vertex(pose, x.toFloat(), y.toFloat(), z.toFloat())
                .color(color.red, color.green, color.blue, color.alpha)
                .normal(normal.x, normal.y, normal.z)
                .endVertex()
        }

        fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
            val normal = lineNormal(x1, y1, z1, x2, y2, z2)
            vertex(x1, y1, z1, normal)
            vertex(x2, y2, z2, normal)
        }

        line(x1, y1, z1, x2, y1, z1)
        line(x2, y1, z1, x2, y1, z2)
        line(x2, y1, z2, x1, y1, z2)
        line(x1, y1, z2, x1, y1, z1)

        line(x1, y2, z1, x2, y2, z1)
        line(x2, y2, z1, x2, y2, z2)
        line(x2, y2, z2, x1, y2, z2)
        line(x1, y2, z2, x1, y2, z1)

        line(x1, y1, z1, x1, y2, z1)
        line(x2, y1, z1, x2, y2, z1)
        line(x2, y1, z2, x2, y2, z2)
        line(x1, y1, z2, x1, y2, z2)
    }

    private fun submitBoxFill(context: LevelRenderContext, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: ANColor) {
        val pose = context.poseStack().last().pose()
        val consumer = context.bufferSource.getBuffer(RenderType.lightning())

        fun vertex(x: Double, y: Double, z: Double) {
            consumer.vertex(pose, x.toFloat(), y.toFloat(), z.toFloat())
                .color(color.red, color.green, color.blue, color.alpha)
                .endVertex()
        }

        fun quad(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, cx: Double, cy: Double, cz: Double, dx: Double, dy: Double, dz: Double) {
            vertex(ax, ay, az)
            vertex(bx, by, bz)
            vertex(cx, cy, cz)
            vertex(dx, dy, dz)
        }

        quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1)
        quad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2)
        quad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2)
        quad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1)
        quad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1)
        quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2)
    }

    private fun lineNormal(start: Vec3, end: Vec3): Vector3f = lineNormal(start.x, start.y, start.z, end.x, end.y, end.z)

    private fun lineNormal(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Vector3f {
        val normal = Vector3f((x2 - x1).toFloat(), (y2 - y1).toFloat(), (z2 - z1).toFloat())
        return if (normal.lengthSquared() > 0f) normal.normalize() else Vector3f(0f, 1f, 0f)
    }
}
