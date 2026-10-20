package com.ada.messenger.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val BOARD_SIZE = 8
const val PATTERN_CUBES = 16
const val PATTERN_COLORS = 5

/**
 * Returns the theme/accent color for each pattern color index (0 to 4).
 * 0: Primary
 * 1: Secondary
 * 2: Tertiary
 * 3: Ruby Coral (#FF6E6E)
 * 4: Emerald Green (#69F0AE)
 */
@Composable
fun getPatternColor(colorIndex: Int?): Color {
    val colorScheme = MaterialTheme.colorScheme
    return when (colorIndex) {
        0    -> colorScheme.primary
        1    -> colorScheme.secondary
        2    -> colorScheme.tertiary
        3    -> Color(0xFFFF6E6E)
        4    -> Color(0xFF69F0AE)
        else -> colorScheme.surfaceVariant
    }
}

@Composable
fun PatternBoardView(
    selectedCells: Map<Int, Int>,
    onCellTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Int = 0,
    onColorSelect: ((Int) -> Unit)? = null,
    readOnly: Boolean = false,
    cellSize: Dp = 36.dp,
    showCounter: Boolean = true,
    showPalette: Boolean = true,
    shakeError: Boolean = false,
) {
    val shakeOffset by animateFloatAsState(
        targetValue = if (shakeError) 1f else 0f,
        animationSpec = if (shakeError) androidx.compose.animation.core.keyframes {
            durationMillis = 400
            0f at 0
            -12f at 50
            12f at 100
            -10f at 150
            10f at 200
            -6f at 250
            6f at 300
            0f at 400
        } else {
            tween(durationMillis = 0)
        },
        label = "shake",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.offset(x = shakeOffset.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (row in 0 until BOARD_SIZE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (col in 0 until BOARD_SIZE) {
                            val index = row * BOARD_SIZE + col
                            PatternCell(
                                index = index,
                                colorIndex = selectedCells[index],
                                readOnly = readOnly,
                                size = cellSize,
                                onClick = { if (!readOnly) onCellTap(index) },
                            )
                        }
                    }
                }
            }
        }

        if (showPalette && !readOnly && onColorSelect != null) {
            Spacer(Modifier.height(8.dp))
            PatternColorPalette(
                selectedColor = activeColor,
                onColorSelect = onColorSelect,
            )
        }

        if (showCounter) {
            Spacer(Modifier.height(if (showPalette && onColorSelect != null) 6.dp else 12.dp))
            PatternCounter(count = selectedCells.size, target = PATTERN_CUBES)
        }
    }
}

/**
 * 5-color palette selector for desktop UI.
 */
@Composable
fun PatternColorPalette(
    selectedColor: Int,
    onColorSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until PATTERN_COLORS) {
            val color = getPatternColor(i)
            val isSelected = selectedColor == i
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.25f else 1.0f,
                animationSpec = spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
                label = "paletteScale-$i"
            )
            val borderAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.35f,
                animationSpec = tween(150),
                label = "paletteBorder-$i"
            )

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .scale(scale)
                    .shadow(if (isSelected) 4.dp else 1.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = Color.White.copy(alpha = borderAlpha),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = enabled) { onColorSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(3.5.dp))
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

/**
 * Palette-based action:
 * - If cell is empty and count < 16: places activeColor.
 * - If cell already has activeColor: removes it (toggle off in 1 tap).
 * - If cell has another color: recolors to activeColor in 1 tap.
 */
fun applyCellAction(current: Map<Int, Int>, index: Int, activeColor: Int): Map<Int, Int> {
    val existing = current[index]
    return when {
        existing == null && current.size < PATTERN_CUBES -> current + (index to activeColor)
        existing == null -> current // limit reached
        existing == activeColor -> current - index // erase on second tap
        else -> current + (index to activeColor) // recolor
    }
}

fun cycleCell(current: Map<Int, Int>, index: Int): Map<Int, Int> {
    val existing = current[index]
    return when {
        existing == null && current.size < PATTERN_CUBES -> current + (index to 0)
        existing == null -> current
        existing < PATTERN_COLORS - 1 -> current + (index to existing + 1)
        else -> current - index
    }
}

@Composable
private fun PatternCell(
    index: Int,
    colorIndex: Int?,
    readOnly: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val targetBgColor = getPatternColor(colorIndex)
    val targetBorderColor = if (colorIndex != null) {
        targetBgColor.copy(alpha = 0.7f)
    } else {
        colorScheme.outline.copy(alpha = 0.25f)
    }

    val bgColor by animateColorAsState(targetValue = targetBgColor, animationSpec = tween(150), label = "cellBg-$index")
    val borderColor by animateColorAsState(targetValue = targetBorderColor, animationSpec = tween(150), label = "cellBorder-$index")
    val infiniteTransition = rememberInfiniteTransition(label = "pulse-$index")
    val pulseFactor by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(animation = tween(700, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "pulse-$index",
    )
    val scale by animateFloatAsState(
        targetValue = if (colorIndex != null) pulseFactor else 1f,
        animationSpec = spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
        label = "scale-$index",
    )
    val elevation by animateDpAsState(
        targetValue = if (colorIndex != null) (6f * pulseFactor).dp else 1.dp,
        animationSpec = spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy),
        label = "elevation-$index",
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(
                brush = if (colorIndex != null) {
                    val dimmed = bgColor.copy(alpha = 0.75f)
                    Brush.radialGradient(colors = listOf(bgColor, dimmed))
                } else {
                    Brush.linearGradient(colors = listOf(bgColor, bgColor.copy(alpha = 0.85f)))
                },
            )
            .border(
                width = if (colorIndex != null) 1.5.dp else 0.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(
                enabled = !readOnly,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = when (colorIndex) {
                    null -> "Cell $index empty"
                    0 -> "Cell $index colour primary"
                    1 -> "Cell $index colour secondary"
                    2 -> "Cell $index colour tertiary"
                    else -> "Cell $index"
                }
            },
    )
}

@Composable
private fun PatternCounter(count: Int, target: Int) {
    val done = count == target
    val textColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "counterColor",
    )
    Text(
        text = "$count / $target",
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 1.5.sp,
        ),
        color = textColor,
    )
}
