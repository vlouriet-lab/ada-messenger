package com.ada.messenger.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Встроенные аватарки: коты, собаки, роботы, рыбки и другие.
 * Назначаются детерминированно по seed (peer ID или group ID) —
 * один и тот же пир всегда получает одну и ту же аватарку у всех.
 */

// ── Gradient helpers ─────────────────────────────────────────────────────────

/** Convert HSL (h: 0..360, s: 0..1, l: 0..1) to Compose Color. */
private fun colorFromHsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f  -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else     -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

/**
 * Derives two gradient colors deterministically from a string seed.
 * Produces vivid but not garish hues — fixed saturation/lightness, only hue varies.
 */
private fun gradientColorsFor(seed: String): Pair<Color, Color> {
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    val hue1 = (Math.floorMod(hash, 360)).toFloat()
    val hue2 = (hue1 + 35f) % 360f
    val c1 = colorFromHsl(hue1, 0.60f, 0.42f)
    val c2 = colorFromHsl(hue2, 0.70f, 0.32f)
    return c1 to c2
}

internal data class AvatarDef(val emoji: String, val bg: Color)

private val PEER_AVATARS = listOf(
    // Коты
    AvatarDef("🐱", Color(0xFFFFE0B2)),
    AvatarDef("😺", Color(0xFFFFCC80)),
    AvatarDef("😸", Color(0xFFFFB74D)),
    // Собаки
    AvatarDef("🐶", Color(0xFFD7CCC8)),
    AvatarDef("🐕", Color(0xFFBCAAA4)),
    // Роботы
    AvatarDef("🤖", Color(0xFFB3E5FC)),
    AvatarDef("👾", Color(0xFFCE93D8)),
    // Рыбки
    AvatarDef("🐠", Color(0xFFB2DFDB)),
    AvatarDef("🐡", Color(0xFFA5D6A7)),
    AvatarDef("🐟", Color(0xFF80DEEA)),
    // Другие зверушки
    AvatarDef("🐰", Color(0xFFF8BBD0)),
    AvatarDef("🦊", Color(0xFFFFCCBC)),
    AvatarDef("🐻", Color(0xFFBCAAA4)),
    AvatarDef("🐼", Color(0xFFCFD8DC)),
    AvatarDef("🐸", Color(0xFFC8E6C9)),
    AvatarDef("🐯", Color(0xFFFFE082)),
    AvatarDef("🐙", Color(0xFFE1BEE7)),
    AvatarDef("🦋", Color(0xFFB3E5FC)),
    AvatarDef("🦎", Color(0xFFDCEDC8)),
    AvatarDef("🐧", Color(0xFFE3F2FD)),
)

private val GROUP_AVATAR = AvatarDef("👥", Color(0xFFE8EAF6))

/** Получить аватарку по индексу (используется для собственного аватара). */
internal fun avatarByIndex(index: Int): AvatarDef = PEER_AVATARS[index.coerceIn(0, PEER_AVATARS.size - 1)]

/** Детерминированно выбирает аватарку по строке-ключу (peer ID и т.п.). */
private fun avatarFor(seed: String): AvatarDef {
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    return PEER_AVATARS[Math.floorMod(hash, PEER_AVATARS.size)]
}

/**
 * Аватарка для прямого чата — градиентный фон + emoji, детерминированный по peer ID.
 */
@Composable
fun PeerAvatar(
    peerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    isOnline: Boolean? = null,
) {
    val av = avatarFor(peerId)
    val (c1, c2) = remember(peerId) { gradientColorsFor(peerId) }
    val brush = Brush.linearGradient(listOf(c1, c2))
    if (isOnline != null) {
        Box(modifier = modifier) {
            AvatarCircle(emoji = av.emoji, bg = av.bg, size = size, brush = brush)
            val dotSize = (size.value * 0.28f).dp
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (isOnline) {
                    if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF4CAF50)
                } else Color(0xFFBDBDBD),
                modifier = Modifier
                    .size(dotSize)
                    .align(Alignment.BottomEnd),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
            ) {}
        }
    } else {
        AvatarCircle(emoji = av.emoji, bg = av.bg, size = size, brush = brush, modifier = modifier)
    }
}

/** Аватарка группового чата — индиго-бирюзовый градиент. */
@Composable
fun GroupAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val brush = Brush.linearGradient(
        listOf(Color(0xFF3D2B99), Color(0xFF00897B)),
    )
    AvatarCircle(
        emoji = GROUP_AVATAR.emoji,
        bg = GROUP_AVATAR.bg,
        size = size,
        brush = brush,
        modifier = modifier,
    )
}

/** Собственная аватарка пользователя — задаётся индексом из сохранённых настроек. */
@Composable
fun OwnAvatar(
    index: Int,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val av = avatarByIndex(index)
    AvatarCircle(emoji = av.emoji, bg = av.bg, size = size, modifier = modifier)
}

/** V-34: Диалог выбора аватарки — круговая сетка с анимацией выбора. */
@Composable
fun AvatarPickerDialog(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите аватарку") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(PEER_AVATARS) { index, av ->
                    val isSelected = index == currentIndex
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.15f else 1f,
                        animationSpec = tween(300),
                        label = "avatarScale",
                    )
                    val borderWidth by animateDpAsState(
                        targetValue = if (isSelected) 3.dp else 0.dp,
                        animationSpec = tween(300),
                        label = "avatarBorder",
                    )
                    val (c1, c2) = remember(index) { gradientColorsFor(PEER_AVATARS[index].emoji) }
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = Color.Transparent,
                        border = if (isSelected) BorderStroke(borderWidth, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .size(52.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(c1, c2)))
                            .clickable { onSelect(index) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = av.emoji, fontSize = 26.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun AvatarCircle(
    emoji: String,
    bg: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (brush != null) Modifier.background(brush)
                else Modifier.background(bg)
            ),
    ) {
        Text(
            text = emoji,
            fontSize = (size.value * 0.52f).sp,
        )
    }
}
