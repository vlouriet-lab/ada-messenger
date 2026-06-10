package com.ada.messenger.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.min

@Composable
fun DesktopQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                1,
                1,
                mapOf(
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }.getOrNull()
    }

    if (matrix == null) {
        Box(
            modifier = modifier
                .size(size)
                .background(Color(0xFFE6E6E6), RoundedCornerShape(24.dp)),
        )
        return
    }

    Canvas(
        modifier = modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(24.dp))
            .padding(14.dp),
    ) {
        val qrSide = min(this.size.width, this.size.height)
        val cellSize = qrSide / matrix.width.toFloat()
        val offsetX = (this.size.width - (matrix.width * cellSize)) / 2f
        val offsetY = (this.size.height - (matrix.height * cellSize)) / 2f

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offsetX + (x * cellSize), offsetY + (y * cellSize)),
                        size = Size(cellSize, cellSize),
                    )
                }
            }
        }
    }
}