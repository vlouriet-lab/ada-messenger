package com.ada.messenger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Constants ─────────────────────────────────────────────────────────────────

const val BOARD_SIZE = 8
const val PATTERN_CUBES = 16
const val BOARD_CELLS = BOARD_SIZE * BOARD_SIZE  // 64
const val PATTERN_COLORS = 3                      // colour indices 0, 1, 2

// ── PatternBoardView ──────────────────────────────────────────────────────────

/**
 * An interactive 8×8 grid where the user taps cells to place up to 16 coloured cubes.
 *
 * **Tap behaviour (cycle):**
 * - Empty cell  → colour 0 (primary)
 * - Colour 0    → colour 1 (secondary)
 * - Colour 1    → colour 2 (tertiary)
 * - Colour 2    → empty (removed)
 *
 * @param selectedCells   Currently selected cells: Map<cellIndex(0-63), colorIndex(0-2)>.
 * @param onCellTap       Invoked with the cell index; the **caller** owns the cycling logic
 *                        (see [cycleCell] helper below).
 * @param readOnly        When true the grid is non-interactive.
 * @param cellSize        Width & height of each cell.
 * @param showCounter     Whether to show the "X / 16" counter below the board.
 * @param shakeError      Toggle true→false to trigger a shake animation.
 */
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
    val haptic = LocalHapticFeedback.current

    // Shake animation
    val shakeOffset by animateFloatAsState(
        targetValue = if (shakeError) 1f else 0f,
        animationSpec = if (shakeError) keyframes {
            durationMillis = 400
            0f   at 0
            -12f at 50
            12f  at 100
            -10f at 150
            10f  at 200
            -6f  at 250
            6f   at 300
            0f   at 400
        } else tween(durationMillis = 0),
        label = "shake"
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
                                colorIndex = selectedCells[index],   // null = empty
                                readOnly = readOnly,
                                size = cellSize,
                                onClick = {
                                    if (!readOnly) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onCellTap(index)
                                    }
                                }
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

/**
 * Convenience function for the parent's `onCellTap` handler.
 * Cycles: empty → 0 → 1 → 2 → empty.
 * Does NOT add a new cube when the limit is already reached (returns unchanged map).
 */
fun cycleCell(current: Map<Int, Int>, index: Int): Map<Int, Int> {
    val existing = current[index]
    return when {
        existing == null && current.size < PATTERN_CUBES -> current + (index to 0)
        existing == null -> current // already at limit, ignore
        existing < PATTERN_COLORS - 1                    -> current + (index to existing + 1)
        else                                             -> current - index
    }
}

// ── Single cell ────────────────────────────────────────────────────────────────

@Composable
private fun PatternCell(
    index: Int,
    colorIndex: Int?,   // null = empty
    readOnly: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Map colour index → Material3 role colours
    val targetBgColor = when (colorIndex) {
        0    -> colorScheme.primary
        1    -> colorScheme.secondary
        2    -> colorScheme.tertiary
        else -> colorScheme.surfaceVariant
    }
    val targetBorderColor = when (colorIndex) {
        0    -> colorScheme.primary.copy(alpha = 0.7f)
        1    -> colorScheme.secondary.copy(alpha = 0.7f)
        2    -> colorScheme.tertiary.copy(alpha = 0.7f)
        else -> colorScheme.outline.copy(alpha = 0.25f)
    }

    val bgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "cellBg-$index"
    )
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(150),
        label = "cellBorder-$index"
    )

    // Subtle pulse for selected cells
    val infiniteTransition = rememberInfiniteTransition(label = "pulse-$index")
    val pulseFactor by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.06f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-$index"
    )
    val scale by animateFloatAsState(
        targetValue = if (colorIndex != null) pulseFactor else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale-$index"
    )

    val elevation by animateDpAsState(
        targetValue = if (colorIndex != null) (6f * pulseFactor).dp else 1.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "elevation-$index"
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
                    Brush.linearGradient(
                        colors = listOf(bgColor, bgColor.copy(alpha = 0.85f))
                    )
                }
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
                    0    -> "Cell $index colour primary"
                    1    -> "Cell $index colour secondary"
                    2    -> "Cell $index colour tertiary"
                    else -> "Cell $index"
                }
            }
    )
}

// ── Counter ────────────────────────────────────────────────────────────────────

@Composable
private fun PatternCounter(count: Int, target: Int) {
    val done = count == target
    val textColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "counterColor"
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
