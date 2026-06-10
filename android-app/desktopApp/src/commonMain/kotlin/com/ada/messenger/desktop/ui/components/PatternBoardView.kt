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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val BOARD_SIZE = 8
const val PATTERN_CUBES = 16
private const val PATTERN_COLORS = 3

@Composable
fun PatternBoardView(
    selectedCells: Map<Int, Int>,
    onCellTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    cellSize: Dp = 36.dp,
    showCounter: Boolean = true,
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

        if (showCounter) {
            Spacer(Modifier.height(12.dp))
            PatternCounter(count = selectedCells.size, target = PATTERN_CUBES)
        }
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
    val targetBgColor = when (colorIndex) {
        0 -> colorScheme.primary
        1 -> colorScheme.secondary
        2 -> colorScheme.tertiary
        else -> colorScheme.surfaceVariant
    }
    val targetBorderColor = when (colorIndex) {
        0 -> colorScheme.primary.copy(alpha = 0.7f)
        1 -> colorScheme.secondary.copy(alpha = 0.7f)
        2 -> colorScheme.tertiary.copy(alpha = 0.7f)
        else -> colorScheme.outline.copy(alpha = 0.25f)
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
