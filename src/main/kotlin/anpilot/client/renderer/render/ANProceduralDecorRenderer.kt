package anpilot.client.renderer.render

import anpilot.client.compat.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import anpilot.client.compat.Identifier
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object ANProceduralDecorRenderer {
    fun roundedBorderDecor(
        context: GuiGraphicsExtractor,
        texture: Identifier,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        options: RoundedBorderDecorOptions,
        scissorArea: ScreenRectangle? = null
    ) {
        if (width <= 0f || height <= 0f || options.baseSize <= 0f || options.density <= 0f) return

        val clampedRadius = radius.coerceIn(0f, min(width, height) * 0.5f)
        val perimeter = roundedRectPerimeter(width, height, clampedRadius)
        val spacing = (options.baseSize / options.density).coerceAtLeast(1f)
        val count = floor(perimeter / spacing).roundToInt().coerceIn(0, options.maxInstances)
        if (count <= 0) return

        val random = Random(options.seed)
        val step = perimeter / count
        repeat(count) { index ->
            val distance = (index + random.nextFloat() * options.positionJitter).coerceAtMost(count.toFloat()) * step
            val sample = sampleRoundedRectBorder(x, y, width, height, clampedRadius, distance % perimeter)
            val scale = lerp(options.minScale, options.maxScale, random.nextFloat())
            val size = options.baseSize * scale
            val randomRotation = lerp(-options.rotationRandomDegrees, options.rotationRandomDegrees, random.nextFloat())
            val rotation = if (options.alignToBorder) sample.tangentDegrees + randomRotation else randomRotation
            ANRender2DEngine.imageRect(
                context,
                texture,
                sample.x + sample.normalX * options.offset,
                sample.y + sample.normalY * options.offset,
                size,
                size,
                options.color,
                scissorArea
            )
        }
    }

    private fun roundedRectPerimeter(width: Float, height: Float, radius: Float): Float {
        return if (radius <= 0f) {
            (width + height) * 2f
        } else {
            2f * (width - 2f * radius) + 2f * (height - 2f * radius) + (2f * PI.toFloat() * radius)
        }
    }

    private fun sampleRoundedRectBorder(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        distance: Float
    ): BorderSample {
        if (radius <= 0f) return sampleRectBorder(x, y, width, height, distance)

        val horizontal = max(0f, width - 2f * radius)
        val vertical = max(0f, height - 2f * radius)
        val arc = PI.toFloat() * radius * 0.5f
        var remaining = distance

        consume(remaining, horizontal)?.let { progress ->
            return BorderSample(x + radius + progress, y, 0f, -1f, 0f)
        }
        remaining -= horizontal

        consume(remaining, arc)?.let { progress ->
            return sampleArc(x + width - radius, y + radius, radius, -90f, progress / arc)
        }
        remaining -= arc

        consume(remaining, vertical)?.let { progress ->
            return BorderSample(x + width, y + radius + progress, 1f, 0f, 90f)
        }
        remaining -= vertical

        consume(remaining, arc)?.let { progress ->
            return sampleArc(x + width - radius, y + height - radius, radius, 0f, progress / arc)
        }
        remaining -= arc

        consume(remaining, horizontal)?.let { progress ->
            return BorderSample(x + width - radius - progress, y + height, 0f, 1f, 180f)
        }
        remaining -= horizontal

        consume(remaining, arc)?.let { progress ->
            return sampleArc(x + radius, y + height - radius, radius, 90f, progress / arc)
        }
        remaining -= arc

        consume(remaining, vertical)?.let { progress ->
            return BorderSample(x, y + height - radius - progress, -1f, 0f, -90f)
        }
        remaining -= vertical

        return sampleArc(x + radius, y + radius, radius, 180f, (remaining / arc).coerceIn(0f, 1f))
    }

    private fun sampleRectBorder(x: Float, y: Float, width: Float, height: Float, distance: Float): BorderSample {
        var remaining = distance
        consume(remaining, width)?.let { return BorderSample(x + it, y, 0f, -1f, 0f) }
        remaining -= width
        consume(remaining, height)?.let { return BorderSample(x + width, y + it, 1f, 0f, 90f) }
        remaining -= height
        consume(remaining, width)?.let { return BorderSample(x + width - it, y + height, 0f, 1f, 180f) }
        remaining -= width
        return BorderSample(x, y + height - remaining.coerceIn(0f, height), -1f, 0f, -90f)
    }

    private fun sampleArc(centerX: Float, centerY: Float, radius: Float, startDegrees: Float, progress: Float): BorderSample {
        val degrees = startDegrees + 90f * progress.coerceIn(0f, 1f)
        val radians = degrees * (PI.toFloat() / 180f)
        val normalX = cos(radians)
        val normalY = sin(radians)
        return BorderSample(
            centerX + normalX * radius,
            centerY + normalY * radius,
            normalX,
            normalY,
            degrees + 90f
        )
    }

    private fun consume(distance: Float, length: Float): Float? {
        if (length <= 0f) return null
        return if (distance <= length) distance else null
    }

    private fun lerp(min: Float, max: Float, delta: Float): Float {
        return min + (max - min) * delta
    }

    data class RoundedBorderDecorOptions(
        val baseSize: Float,
        val density: Float = 1f,
        val minScale: Float = 0.75f,
        val maxScale: Float = 1.25f,
        val rotationRandomDegrees: Float = 35f,
        val offset: Float = 0f,
        val seed: Long = 0L,
        val color: Int = -1,
        val alignToBorder: Boolean = true,
        val positionJitter: Float = 1f,
        val maxInstances: Int = 256
    )

    private data class BorderSample(
        val x: Float,
        val y: Float,
        val normalX: Float,
        val normalY: Float,
        val tangentDegrees: Float
    )
}
