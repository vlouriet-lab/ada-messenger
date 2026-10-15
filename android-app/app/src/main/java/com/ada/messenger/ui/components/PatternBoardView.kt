package com.ada.messenger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
const val PATTERN_COLORS = 5                      // colour indices 0, 1, 2, 3, 4

/**
 * Returns the theme/accent color for each pattern color index (0 to 4).
 * 0: Primary (Indigo)
 * 1: Secondary (Teal)
 * 2: Tertiary (Peach/Amber)
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
        3    -> Color(0xFFFF6E6E) // Ruby / Coral
        4    -> Color(0xFF69F0AE) // Emerald / Mint
        else -> colorScheme.surfaceVariant
    }
}

// ── PatternBoardView ──────────────────────────────────────────────────────────

/**
 * An interactive 8×8 grid where the user taps cells to place up to 16 coloured cubes.
 *
 * Supports both palette-based single-tap painting and cycling.
 *
 * @param selectedCells   Currently selected cells: Map<cellIndex(0-63), colorIndex(0-4)>.
 * @param onCellTap       Invoked with the cell index when a cell is clicked.
 * @param activeColor     Currently selected color index in the palette (0 to 4).
 * @param onColorSelect   Callback when user taps a color in the palette. When null, palette is hidden.
 * @param readOnly        When true the grid is non-interactive.
 * @param cellSize        Width & height of each cell.
 * @param showCounter     Whether to show the "X / 16" counter below the board.
 * @param showPalette     Whether to display the 5-color palette selector.
 * @param shakeError      Toggle true→false to trigger a shake animation.
 */
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

    val density = LocalDensity.current
    val cellPx = with(density) { cellSize.toPx() }
    val spacePx = with(density) { 3.dp.toPx() }
    val stridePx = cellPx + spacePx
    var lastPaintedIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .offset(x = shakeOffset.dp)
                .then(
                    if (!readOnly) {
                        Modifier.pointerInput(readOnly, activeColor) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val col = (offset.x / stridePx).toInt()
                                    val row = (offset.y / stridePx).toInt()
                                    if (col in 0 until BOARD_SIZE && row in 0 until BOARD_SIZE) {
                                        val idx = row * BOARD_SIZE + col
                                        lastPaintedIndex = idx
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onCellTap(idx)
                                    }
                                },
                                onDragEnd = { lastPaintedIndex = null },
                                onDragCancel = { lastPaintedIndex = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pos = change.position
                                    val col = (pos.x / stridePx).toInt()
                                    val row = (pos.y / stridePx).toInt()
                                    if (col in 0 until BOARD_SIZE && row in 0 until BOARD_SIZE) {
                                        val idx = row * BOARD_SIZE + col
                                        if (idx != lastPaintedIndex) {
                                            lastPaintedIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onCellTap(idx)
                                        }
                                    }
                                }
                            )
                        }
                    } else Modifier
                )
        ) {
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

        if (showPalette && !readOnly && onColorSelect != null) {
            Spacer(Modifier.height(10.dp))
            PatternColorPalette(
                selectedColor = activeColor,
                onColorSelect = onColorSelect,
            )
        }

        if (showCounter) {
            Spacer(Modifier.height(if (showPalette && onColorSelect != null) 6.dp else 12.dp))
            PatternCounter(selectedCells = selectedCells, target = PATTERN_CUBES)
        }
    }
}

/**
 * 5-color palette selector. Tapping a circle selects the active color for placement.
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
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until PATTERN_COLORS) {
            val color = getPatternColor(i)
            val isSelected = selectedColor == i
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.25f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "paletteScale-$i"
            )
            val borderAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.35f,
                animationSpec = tween(150),
                label = "paletteBorder-$i"
            )

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .scale(scale)
                    .shadow(if (isSelected) 6.dp else 2.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(color)
                    .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = Color.White.copy(alpha = borderAlpha),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable(enabled = enabled) { onColorSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
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

/**
 * Convenience function for the parent's `onCellTap` handler (legacy cycling).
 * Cycles: empty → 0 → 1 → 2 → 3 → 4 → empty.
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

    // Map colour index → palette colours
    val targetBgColor = getPatternColor(colorIndex)
    val targetBorderColor = if (colorIndex != null) {
        targetBgColor.copy(alpha = 0.7f)
    } else {
        colorScheme.outline.copy(alpha = 0.25f)
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
                    3    -> "Cell $index colour ruby"
                    4    -> "Cell $index colour emerald"
                    else -> "Cell $index"
                }
            }
    )
}

// ── Counter ────────────────────────────────────────────────────────────────────

@Composable
private fun PatternCounter(selectedCells: Map<Int, Int>, target: Int) {
    val count = selectedCells.size
    val done = count == target
    val textColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "counterColor"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$count / $target",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 1.5.sp,
            ),
            color = textColor,
        )

        if (count > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (colorIdx in 0 until PATTERN_COLORS) {
                    val colorCount = selectedCells.values.count { it == colorIdx }
                    if (colorCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(getPatternColor(colorIdx))
                            )
                            Text(
                                text = "$colorCount",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
