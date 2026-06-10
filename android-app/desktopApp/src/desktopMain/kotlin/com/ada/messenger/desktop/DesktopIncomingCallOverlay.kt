package com.ada.messenger.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ada.messenger.desktop.model.DesktopIncomingCall
import com.ada.messenger.desktop.ui.components.InitialsAvatar

@Composable
fun DesktopIncomingCallOverlay(
    incomingCall: DesktopIncomingCall?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    if (incomingCall == null) {
        return
    }

    val accent = if (incomingCall.hasVideo) Color(0xFF73D5FF) else Color(0xFF79E2A0)
    val ringTransition = rememberInfiniteTransition()
    val ringOuterAlpha by ringTransition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val ringInnerAlpha by ringTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, delayMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = if (incomingCall.hasVideo) {
                        listOf(Color(0xFF173B56), Color(0xFF08131D), Color(0xFF02060B))
                    } else {
                        listOf(Color(0xFF18324A), Color(0xFF07111A), Color(0xFF02060B))
                    },
                    radius = 1320f,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x18000000), Color(0xD002060B)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 34.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IncomingCallChip(
                text = incomingCallChipLabel(incomingCall),
                accent = accent,
            )
            Text(
                text = incomingCallDisplayName(incomingCall),
                color = Color(0xFFF5F9FF),
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontSize = 36.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = incomingCallSubtitle(incomingCall),
                color = Color(0xFFB5C6D8),
                fontWeight = FontWeight.Medium,
                style = TextStyle(fontSize = 16.sp),
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Box(
                modifier = Modifier.size(264.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(248.dp)
                        .border(3.dp, accent.copy(alpha = ringOuterAlpha), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(216.dp)
                        .border(3.dp, accent.copy(alpha = ringInnerAlpha), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(188.dp)
                        .shadow(30.dp, CircleShape, clip = false),
                    contentAlignment = Alignment.Center,
                ) {
                    InitialsAvatar(
                        name = incomingCallDisplayName(incomingCall),
                        size = 188.dp,
                        fontSize = 68.sp,
                    )
                }
            }

            Text(
                text = incomingCallPrompt(incomingCall),
                color = Color(0xFFB1C0CF),
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 15.sp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE602060B)),
                    ),
                )
                .padding(start = 28.dp, end = 28.dp, top = 48.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IncomingCallActionButton(
                    icon = Icons.Default.CallEnd,
                    label = "Отклонить",
                    containerColor = Color(0xFFE35D6A),
                    contentColor = Color(0xFFFFF3F5),
                    onClick = onDecline,
                )
                IncomingCallActionButton(
                    icon = if (incomingCall.hasVideo) Icons.Default.Videocam else Icons.Default.Call,
                    label = if (incomingCall.hasVideo) "Ответить видео" else "Ответить",
                    containerColor = accent,
                    contentColor = Color(0xFF06202D),
                    onClick = onAnswer,
                )
            }
        }
    }
}

@Composable
private fun IncomingCallChip(
    text: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x24161F29))
            .border(1.dp, accent.copy(alpha = 0.52f), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFF6FBFF),
            fontWeight = FontWeight.Medium,
            style = TextStyle(fontSize = 13.sp),
        )
    }
}

@Composable
private fun IncomingCallActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .shadow(16.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(containerColor)
                .border(1.dp, Color(0x33FFFFFF), CircleShape)
                .background(containerColor)
                .padding(0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .size(30.dp)
                    .align(Alignment.Center),
            )
        }
        Text(
            text = label,
            color = Color(0xFFF2F7FD),
            fontWeight = FontWeight.Medium,
            style = TextStyle(fontSize = 13.sp),
        )
    }
}

private fun incomingCallDisplayName(incomingCall: DesktopIncomingCall): String {
    return incomingCall.displayName.ifBlank {
        buildString {
            append(incomingCall.peerId.take(8))
            append('…')
        }
    }
}

private fun incomingCallChipLabel(incomingCall: DesktopIncomingCall): String {
    return when {
        incomingCall.groupId != null && incomingCall.hasVideo -> "Входящий групповой видеозвонок"
        incomingCall.groupId != null -> "Входящий групповой звонок"
        incomingCall.hasVideo -> "Входящий видеозвонок"
        else -> "Входящий голосовой звонок"
    }
}

private fun incomingCallSubtitle(incomingCall: DesktopIncomingCall): String {
    return when {
        incomingCall.groupId != null && incomingCall.participants.isNotEmpty() -> {
            val others = incomingCall.participants.size.coerceAtLeast(1)
            "${if (incomingCall.hasVideo) "Видео" else "Голос"} · участников: $others"
        }
        incomingCall.groupId != null -> {
            if (incomingCall.hasVideo) "Групповой видеозвонок" else "Групповой голосовой звонок"
        }
        incomingCall.hasVideo -> "Собеседник приглашает вас в прямой видеозвонок"
        else -> "Собеседник приглашает вас в прямой голосовой звонок"
    }
}

private fun incomingCallPrompt(incomingCall: DesktopIncomingCall): String {
    return if (incomingCall.hasVideo) {
        "Примите звонок, чтобы сразу перейти в video overlay со всеми desktop controls."
    } else {
        "Примите звонок, чтобы сразу перейти в voice overlay с mute и audio output controls."
    }
}