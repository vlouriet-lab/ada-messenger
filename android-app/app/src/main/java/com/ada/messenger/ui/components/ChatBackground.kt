
package com.ada.messenger.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

enum class ChatPattern {
    SOLID, ROMANCE, GEOMETRY, LIFESTYLE
}

@Composable
fun Modifier.chatBackgroundPattern(
    pattern: ChatPattern,
    isDark: Boolean = isSystemInDarkTheme()
): Modifier {
    if (pattern == ChatPattern.SOLID) return this
    
    // Colors for the pattern lines based on theme
    val lineColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)
    val bgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)

    return this.drawBehind {
        // Draw base color
        drawRect(bgColor)
        
        val w = size.width
        val h = size.height
        val step = 40.dp.toPx()
        
        when (pattern) {
            ChatPattern.ROMANCE -> {
                // Romance: wavy or diagonal crossing lines resembling hearts/diamonds
                for (x in 0..(w / step).toInt() * 2) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x * step, 0f),
                        end = Offset(x * step - h, h),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = lineColor,
                        start = Offset(x * step - w, 0f),
                        end = Offset(x * step - w + h, h),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            ChatPattern.GEOMETRY -> {
                // Geometry: triangles / grid
                for (y in 0..(h / step).toInt()) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y * step),
                        end = Offset(w, y * step),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                for (x in 0..(w / step).toInt()) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x * step, 0f),
                        end = Offset(x * step, h),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            ChatPattern.LIFESTYLE -> {
                // Lifestyle: concentric circles or dots
                val dotRadius = 4.dp.toPx()
                for (x in 0..(w / step).toInt()) {
                    for (y in 0..(h / step).toInt()) {
                        drawCircle(
                            color = lineColor,
                            radius = dotRadius,
                            center = Offset(x * step + step/2, y * step + step/2)
                        )
                    }
                }
            }
            else -> {}
        }
    }
}

