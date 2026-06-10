package com.ada.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a QR code for the given [content] string.
 *
 * Uses ZXing core to generate a Bitmap synchronously (small payload, fast).
 * [size] controls both width and height of the rendered image.
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 256.dp,
) {
    val bitmap = remember(content) { generateQrBitmap(content, 512) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            modifier = modifier.size(size),
        )
    } else {
        // Fallback placeholder
        Box(modifier = modifier.size(size).background(androidx.compose.ui.graphics.Color.LightGray))
    }
}

private fun generateQrBitmap(content: String, pixelSize: Int): Bitmap? = runCatching {
    val writer = QRCodeWriter()
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, pixelSize, pixelSize, hints)
    val bmp = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.RGB_565)
    for (x in 0 until pixelSize) {
        for (y in 0 until pixelSize) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    bmp
}.getOrNull()
