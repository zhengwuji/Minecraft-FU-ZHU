package anpilot.client.features.gui

import anpilot.client.features.manager.ANConfigManager
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import anpilot.client.compat.Identifier
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object ANDecorTextureManager {
    private val fallbackDecor = Identifier("anpilotclient", "textures/decor/flower.png")
    private val customDecor = Identifier("anpilotclient", "custom_decor/click_gui")
    private val logger = LoggerFactory.getLogger("ANDecorTextureManager")

    private var loadedFile: File? = null
    private var loadedModified = -1L

    fun texture(fileName: String): Identifier {
        val selectedFile = ANConfigManager.customDecorFile(fileName)
        if (!selectedFile.isFile) return fallbackDecor

        val modified = selectedFile.lastModified()
        if (loadedFile?.absolutePath == selectedFile.absolutePath && loadedModified == modified) {
            return customDecor
        }

        return runCatching {
            val image = readImage(selectedFile)
            Minecraft.getInstance().textureManager.register(
                customDecor,
                DynamicTexture(image)
            )
            loadedFile = selectedFile
            loadedModified = modified
            logger.info("Loaded custom Decor texture: {}", selectedFile.absolutePath)
            customDecor
        }.getOrElse {
            logger.warn("Failed to load custom Decor texture: ${selectedFile.absolutePath}", it)
            fallbackDecor
        }
    }

    private fun readImage(file: File): NativeImage {
        val buffered = ImageIO.read(file)
            ?: throw IllegalArgumentException("Unsupported image format: ${file.absolutePath}")
        val argb = if (buffered.type == BufferedImage.TYPE_INT_ARGB) {
            buffered
        } else {
            BufferedImage(buffered.width, buffered.height, BufferedImage.TYPE_INT_ARGB).also { converted ->
                val graphics = converted.createGraphics()
                graphics.drawImage(buffered, 0, 0, null)
                graphics.dispose()
            }
        }
        val image = NativeImage(argb.width, argb.height, false)
        for (y in 0 until argb.height) {
            for (x in 0 until argb.width) {
                image.setPixelRGBA(x, y, argb.getRGB(x, y))
            }
        }
        return image
    }
}
