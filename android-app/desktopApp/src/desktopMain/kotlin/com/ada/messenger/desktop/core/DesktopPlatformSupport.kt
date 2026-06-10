package com.ada.messenger.desktop.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal object DesktopFileDialogs {
    fun pickTextFile(title: String): String? = pickFile(
        title = title,
        description = "Text or JSON",
        extensions = arrayOf("json", "txt"),
    )

    fun pickImageFile(title: String): String? = pickFile(
        title = title,
        description = "Image files",
        extensions = arrayOf("png", "jpg", "jpeg", "webp", "bmp", "gif"),
    )

    fun pickAnyFile(title: String): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            isMultiSelectionEnabled = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    fun pickSaveFile(title: String, suggestedFileName: String): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            isMultiSelectionEnabled = false
            selectedFile = Path.of(suggestedFileName).toFile()
        }
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    fun pickSaveTextFile(
        title: String,
        suggestedFileName: String,
        description: String = "JSON files",
        extensions: Array<String> = arrayOf("json"),
    ): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter(description, *extensions)
            selectedFile = Path.of(suggestedFileName).toFile()
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null
        }
        val selectedFile = chooser.selectedFile ?: return null
        val normalizedPath = selectedFile.toPath()
        val needsExtension = extensions.isNotEmpty() && normalizedPath.fileName.toString().substringAfterLast('.', "")
            .lowercase() !in extensions.map { it.lowercase() }
        return if (needsExtension) {
            normalizedPath.resolveSibling("${normalizedPath.fileName}.${extensions.first()}").toString()
        } else {
            normalizedPath.toString()
        }
    }

    private fun pickFile(
        title: String,
        description: String,
        extensions: Array<String>,
    ): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter(description, *extensions)
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }
}

internal fun readTextFile(path: String, maxBytes: Int = 512 * 1024): String? = runCatching {
    val filePath = Paths.get(path)
    val bytes = Files.readAllBytes(filePath)
    require(bytes.size <= maxBytes) { "file is too large" }
    String(bytes, Charsets.UTF_8)
}.getOrNull()

internal fun writeTextFile(path: String, text: String): Boolean = runCatching {
    val filePath = Path.of(path)
    filePath.parent?.let(Files::createDirectories)
    Files.writeString(filePath, text, Charsets.UTF_8)
    true
}.getOrDefault(false)

internal fun decodeQrTextFromImage(path: String): String? = runCatching {
    val image = ImageIO.read(Path.of(path).toFile()) ?: error("unable to decode image")
    val source = BufferedImageQrLuminanceSource(image)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    MultiFormatReader().decode(bitmap).text
}.getOrNull()

internal fun openSystemPath(path: String): Boolean = runCatching {
    val file = Path.of(path).toFile()
    if (!file.exists()) {
        false
    } else {
        Desktop.getDesktop().open(file)
        true
    }
}.getOrDefault(false)

private class BufferedImageQrLuminanceSource(image: BufferedImage) : LuminanceSource(image.width, image.height) {
    private val luminances: ByteArray = ByteArray(image.width * image.height).also { output ->
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        for (index in pixels.indices) {
            val argb = pixels[index]
            val red = (argb shr 16) and 0xFF
            val green = (argb shr 8) and 0xFF
            val blue = argb and 0xFF
            output[index] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
        }
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        val target = row?.takeIf { it.size >= width } ?: ByteArray(width)
        System.arraycopy(luminances, y * width, target, 0, width)
        return target
    }

    override fun getMatrix(): ByteArray = luminances.clone()
}