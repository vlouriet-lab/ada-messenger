package com.ada.messenger.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val avatarBackgroundColors = listOf(
    Color(0xFF1565C0), // blue
    Color(0xFF6A1B9A), // purple
    Color(0xFFC62828), // red
    Color(0xFF2E7D32), // green
    Color(0xFFE65100), // orange
    Color(0xFF00695C), // teal
    Color(0xFF283593), // indigo
    Color(0xFF558B2F), // lime green
)

fun avatarColorForName(name: String): Color {
    if (name.isBlank()) return avatarBackgroundColors[0]
    val index = kotlin.math.abs(name.fold(0) { acc, c -> acc * 31 + c.code }) % avatarBackgroundColors.size
    return avatarBackgroundColors[index]
}

@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    fontSize: TextUnit = 15.sp,
    isOnline: Boolean? = null,
) {
    val initial = remember(name) {
        name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
    val bgColor = remember(name) { avatarColorForName(name) }

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                textAlign = TextAlign.Center,
            )
        }

        if (isOnline != null) {
            val dotSize = (size.value * 0.28f).dp
            val borderBg = MaterialTheme.colorScheme.background
            val dotColor = if (isOnline) Color(0xFF66BB6A) else Color(0xFF616161)
            // Outer box acts as the border ring (uses background color to separate from avatar)
            Box(
                modifier = Modifier
                    .size(dotSize + 3.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(borderBg),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }
    }
}
