package com.ada.messenger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class AdaHexCell(
    val normDist: Float,
    val path: Path,
)

@Composable
fun AdaHexBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val dark = colorScheme.background.luminance() < 0.45f
    val transition = rememberInfiniteTransition(label = "ada_hex_background")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ada_hex_phase",
    )

    val base = if (dark) Color(0xFF070812) else Color(0xFFFAF8FF)
    val upperA = if (dark) Color(0x3F2D2466) else Color(0x40E8E3FF)
    val upperB = if (dark) Color(0x26004F4D) else Color(0x22B2DFDB)
    val lowerA = if (dark) Color(0x1C002F36) else Color(0x10FFFFFF)
    val line = if (dark) Color(0xFFA855F7) else Color(0xFF4A3DB5)
    val glint = if (dark) Color(0xFF5AC8C4) else Color(0xFF00796B)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val cells = buildAdaHexCells(size)
                val upperWash = Brush.linearGradient(
                    colors = listOf(upperA, upperB, Color.Transparent),
                    start = Offset(size.width * 0.2f, -size.height * 0.12f),
                    end = Offset(size.width * 0.95f, size.height * 0.48f),
                )
                val lowerWash = Brush.linearGradient(
                    colors = listOf(Color.Transparent, lowerA),
                    start = Offset(0f, size.height * 0.35f),
                    end = Offset(size.width, size.height),
                )
                val stroke = Stroke(width = 1.05.dp.toPx())
                onDrawBehind {
                    drawRect(base)
                    drawRect(upperWash)
                    drawRect(lowerWash)

                    cells.forEachIndexed { index, cell ->
                        val wave = ((sin((phase * 2f * PI).toFloat() - cell.normDist * 3f) + 1f) / 2f)
                        val alpha = (0.03f + wave * 0.075f * (1f - cell.normDist)).coerceIn(0f, 0.12f)
                        val color = if (index % 11 == 0) glint else line
                        drawPath(
                            path = cell.path,
                            color = color.copy(alpha = alpha),
                            style = stroke,
                        )
                    }
                }
            },
        content = content,
    )
}

private fun buildAdaHexCells(size: Size): List<AdaHexCell> {
    if (size.width <= 0f || size.height <= 0f) return emptyList()

    val radius = 126f
    val hexWidth = sqrt(3f) * radius
    val rowStep = radius * 1.5f
    val cols = ceil(size.width / hexWidth).toInt() + 2
    val rows = ceil(size.height / rowStep).toInt() + 2
    val maxDist = size.width * 0.9f
    val cells = ArrayList<AdaHexCell>(cols * rows)

    for (row in -1 until rows) {
        for (col in -1 until cols) {
            val odd = col % 2 != 0
            val cx = col * hexWidth + if (odd) hexWidth / 2f else 0f
            val cy = row * rowStep + if (odd) rowStep / 2f else 0f
            val dx = cx - size.width * 0.5f
            val dy = cy - size.height * 0.24f
            val dist = sqrt(dx * dx + dy * dy)
            val normDist = (dist / maxDist).coerceIn(0f, 1f)
            val path = Path()
            for (point in 0 until 6) {
                val angle = (PI / 180.0 * (60.0 * point - 30.0)).toFloat()
                val x = cx + radius * cos(angle)
                val y = cy + radius * sin(angle)
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            cells.add(AdaHexCell(normDist = normDist, path = path))
        }
    }

    return cells
}