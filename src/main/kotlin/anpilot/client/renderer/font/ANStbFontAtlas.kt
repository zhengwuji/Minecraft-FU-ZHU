package anpilot.client.renderer.font

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBTTPackContext
import org.lwjgl.stb.STBTTPackRange
import org.lwjgl.stb.STBTTPackedchar
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype
import org.lwjgl.system.MemoryStack
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

class ANStbFontAtlas private constructor(
    private val fontBuffer: ByteBuffer,
    private val pixelHeight: Int,
    private val atlasSize: Int,
    val renderScale: Float
) : AutoCloseable {
    private val fontInfo = STBTTFontinfo.create()
    private val bitmap = BufferUtils.createByteBuffer(atlasSize * atlasSize)
    private val packContext = STBTTPackContext.create()
    private val glyphs = HashMap<Int, ANGlyph>()
    private val failedGlyphs = HashSet<Int>()
    private val nativeImage = NativeImage(atlasSize, atlasSize, false)
    private val texture = DynamicTexture(nativeImage)
    private val scale: Float
    private val ascent: Float
    private var dirty = false

    val height: Int get() = (pixelHeight * renderScale).toInt()

    init {
        if (!STBTruetype.stbtt_InitFont(fontInfo, fontBuffer)) {
            throw IllegalStateException("Failed to initialize TTF font")
        }
        STBTruetype.stbtt_PackBegin(packContext, bitmap, atlasSize, atlasSize, 0, 1)
        scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, pixelHeight.toFloat())
        ascent = MemoryStack.stackPush().use { stack ->
            val asc = stack.mallocInt(1)
            STBTruetype.stbtt_GetFontVMetrics(fontInfo, asc, null, null)
            asc.get(0).toFloat() * scale
        }
        packRange(32, 95)
        upload()
    }

    fun glyph(codepoint: Int): ANGlyph? {
        if (codepoint == '\n'.code || codepoint == '\r'.code) return null
        ensureGlyph(codepoint)
        return glyphs[codepoint] ?: glyphs[32]
    }

    fun width(text: String, scale: Float = 1f): Int {
        var width = 0f
        text.codePoints().forEach { codepoint -> width += (glyph(codepoint)?.xAdvance ?: 0f) * renderScale * scale }
        return width.toInt()
    }

    fun height(scale: Float = 1f): Int = (pixelHeight * renderScale * scale).toInt()

    fun baselineY(y: Float, scale: Float = 1f): Float = y + ascent * renderScale * scale

    fun uploadIfNeeded() {
        if (dirty) upload()
    }

    override fun close() {
        texture.close()
        nativeImage.close()
        packContext.free()
        fontInfo.free()
    }

    private fun ensureGlyph(codepoint: Int) {
        if (glyphs.containsKey(codepoint) || failedGlyphs.contains(codepoint)) return
        packRange(codepoint, 1)
    }

    private fun packRange(start: Int, count: Int) {
        val packed = STBTTPackedchar.create(count)
        val range = STBTTPackRange.create(1)
        range.put(STBTTPackRange.create().set(pixelHeight.toFloat(), start, null, count, packed, 4.toByte(), 4.toByte()))
        range.flip()

        if (!STBTruetype.stbtt_PackFontRanges(packContext, fontBuffer, 0, range)) {
            for (i in 0 until count) failedGlyphs += start + i
            return
        }

        val ipw = 1f / atlasSize
        val iph = 1f / atlasSize
        for (i in 0 until count) {
            val packedChar = packed.get(i)
            if (packedChar.xadvance() == 0f) continue
            val codepoint = start + i
            glyphs[codepoint] = ANGlyph(
                packedChar.xoff(),
                packedChar.yoff(),
                packedChar.xoff2(),
                packedChar.yoff2(),
                packedChar.x0() * ipw,
                packedChar.y0() * iph,
                packedChar.x1() * ipw,
                packedChar.y1() * iph,
                max(1f, packedChar.xadvance())
            )
        }
        val minX = (packed.minOfOrNull { it.x0().toInt() } ?: 0).coerceIn(0, atlasSize)
        val minY = (packed.minOfOrNull { it.y0().toInt() } ?: 0).coerceIn(0, atlasSize)
        val endX = (packed.maxOfOrNull { it.x1().toInt() } ?: 0).coerceIn(0, atlasSize)
        val endY = (packed.maxOfOrNull { it.y1().toInt() } ?: 0).coerceIn(0, atlasSize)
        copyBitmapToImage(minX, minY, endX, endY)
        dirty = true
    }

    private fun upload() {
        texture.upload()
        dirty = false
    }

    private fun copyBitmapToImage(startX: Int, startY: Int, endX: Int, endY: Int) {
        for (y in startY until endY) {
            val row = y * atlasSize
            for (x in startX until endX) {
                val alpha = bitmap.get(row + x).toInt() and 255
                nativeImage.setPixelRGBA(x, y, (alpha shl 24) or 0xFFFFFF)
            }
        }
    }

    companion object {
        private const val DEFAULT_ATLAS_SIZE = 2048
        private const val DEFAULT_PIXEL_HEIGHT = 30
        private const val DEFAULT_RENDER_SCALE = 0.44f

        fun load(resource: InputStream, pixelHeight: Int = DEFAULT_PIXEL_HEIGHT, atlasSize: Int = DEFAULT_ATLAS_SIZE, renderScale: Float = DEFAULT_RENDER_SCALE): ANStbFontAtlas {
            val bytes = resource.use { it.readBytes() }
            val buffer = BufferUtils.createByteBuffer(bytes.size)
            buffer.put(bytes).flip()
            return ANStbFontAtlas(buffer, pixelHeight, atlasSize, renderScale)
        }
    }
}
