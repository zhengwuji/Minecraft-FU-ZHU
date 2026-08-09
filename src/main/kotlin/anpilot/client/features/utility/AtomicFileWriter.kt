package anpilot.client.features.utility

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AtomicFileWriter {
    fun writeString(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${file.name}.tmp")
        tmpFile.writeText(content, Charsets.UTF_8)

        try {
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
