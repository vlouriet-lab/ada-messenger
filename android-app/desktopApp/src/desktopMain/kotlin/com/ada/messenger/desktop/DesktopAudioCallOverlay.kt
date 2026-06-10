package com.ada.messenger.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ada.messenger.desktop.core.DesktopVideoCallUiState
import com.ada.messenger.desktop.model.DesktopActiveCall
import com.ada.messenger.desktop.ui.components.InitialsAvatar

@Composable
fun DesktopAudioCallOverlay(
    activeCall: DesktopActiveCall?,
    audioState: DesktopVideoCallUiState,
    onSetLocalAudioEnabled: (Boolean) -> Unit,
    onCycleAudioOutput: () -> Unit,
    onHangup: () -> Unit,
) {
    if (activeCall == null) {
        return
    }

    var collapsed by remember(activeCall.callId) { mutableStateOf(false) }
    val subtitle = formatAudioCallSubtitle(activeCall)
    val outputSummary = audioState.activeAudioOutputLabel?.let { "Выход: ${compactAudioLabel(it)}" } ?: "Выход: системный"

    if (collapsed) {
        Box(modifier = Modifier.fillMaxSize()) {
            CompactAudioCallCard(
                activeCall = activeCall,
                subtitle = subtitle,
                audioEnabled = audioState.localAudioEnabled,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                onExpand = { collapsed = false },
                onHangup = onHangup,
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF18324A), Color(0xFF07111A), Color(0xFF02060B)),
                    radius = 1200f,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x22000000), Color(0xAA02060B)),
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
            AudioStatusChip(
                text = if (audioState.localAudioEnabled) "Голосовой звонок" else "Микрофон выключен",
                accent = if (audioState.localAudioEnabled) Color(0xFF6DDC8B) else Color(0xFFFFC857),
            )
            Text(
                text = activeCall.displayName,
                color = Color(0xFFF5F9FF),
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontSize = 34.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color(0xFFB5C6D8),
                fontWeight = FontWeight.Medium,
                style = TextStyle(fontSize = 16.sp),
            )
            AudioInfoChip(text = outputSummary)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .shadow(28.dp, CircleShape, clip = false),
                contentAlignment = Alignment.Center,
            ) {
                InitialsAvatar(
                    name = activeCall.displayName,
                    size = 196.dp,
                    fontSize = 72.sp,
                )
            }

            Text(
                text = if (audioState.localAudioEnabled) {
                    "Микрофон активен, звук идёт через выбранный desktop output."
                } else {
                    "Вы в звонке, но ваш микрофон сейчас выключен."
                },
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
                .padding(start = 28.dp, end = 28.dp, top = 48.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AudioRoundButton(
                    icon = if (audioState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    label = if (audioState.localAudioEnabled) "Микрофон" else "Без звука",
                    active = !audioState.localAudioEnabled,
                    onClick = { onSetLocalAudioEnabled(!audioState.localAudioEnabled) },
                )
                AudioRoundButton(
                    icon = Icons.Default.CallEnd,
                    label = "Завершить",
                    destructive = true,
                    onClick = onHangup,
                )
                AudioRoundButton(
                    icon = Icons.Default.VolumeUp,
                    label = if (audioState.audioOutputCount > 1) "Выход" else "1 выход",
                    enabled = audioState.audioOutputCount > 1,
                    onClick = onCycleAudioOutput,
                )
                AudioRoundButton(
                    icon = Icons.Default.CloseFullscreen,
                    label = "Свернуть",
                    onClick = { collapsed = true },
                )
            }
        }
    }
}

@Composable
private fun CompactAudioCallCard(
    activeCall: DesktopActiveCall,
    subtitle: String,
    audioEnabled: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onHangup: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .width(320.dp)
            .height(188.dp)
            .shadow(28.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF18324A), Color(0xFF07111A)),
                ),
            )
            .border(1.dp, Color(0x80314A60), shape)
            .clickable(onClick = onExpand),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InitialsAvatar(
                    name = activeCall.displayName,
                    size = 56.dp,
                    fontSize = 22.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = activeCall.displayName,
                        color = Color(0xFFF4F8FE),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        color = Color(0xFFB9C7D6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniAudioChip(text = if (audioEnabled) "Микрофон" else "Mute")
                MiniAudioChip(text = "Audio call")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactAudioIconButton(icon = Icons.Default.OpenInFull, onClick = onExpand)
                CompactAudioIconButton(icon = Icons.Default.CallEnd, destructive = true, onClick = onHangup)
            }
        }
    }
}

@Composable
private fun AudioStatusChip(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xCC0D1924))
            .border(1.dp, Color(0x6633485C), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = text,
            color = Color(0xFFF1F6FD),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AudioInfoChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xCC111D29))
            .border(1.dp, Color(0x6633485C), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFD4DFEA),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AudioRoundButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val surfaceColor = when {
        !enabled -> Color(0x3D31404F)
        destructive -> Color(0xFFE45454)
        active -> Color(0xFFF4F7FC)
        else -> Color(0x401E2E3E)
    }
    val iconTint = when {
        !enabled -> Color(0xFF718395)
        destructive -> Color.White
        active -> Color(0xFF08111A)
        else -> Color(0xFFF1F6FD)
    }
    val labelTint = if (enabled) Color(0xFFDDE7F2) else Color(0xFF7D8FA1)

    Column(
        modifier = Modifier.width(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (destructive) 76.dp else 68.dp)
                .clip(CircleShape)
                .background(surfaceColor)
                .border(
                    width = 1.dp,
                    color = if (enabled) Color(0x66384D62) else Color(0x44313D48),
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(if (destructive) 28.dp else 24.dp),
            )
        }

        Text(
            text = label,
            color = labelTint,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.sp),
        )
    }
}

@Composable
private fun CompactAudioIconButton(
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (destructive) Color(0xFFD94F4F) else Color(0xB8132130))
            .border(1.dp, Color(0x66364859), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MiniAudioChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xB0121D28))
            .border(1.dp, Color(0x55344759), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFD8E3EE),
            style = TextStyle(fontSize = 12.sp),
        )
    }
}

private fun formatAudioCallSubtitle(activeCall: DesktopActiveCall): String {
    val normalized = activeCall.state.trim().lowercase()
    if (normalized.isEmpty()) {
        return "Соединение устанавливается"
    }
    return when {
        normalized.contains("ring") -> if (activeCall.isOutgoing) "Вызываем собеседника" else "Входящий звонок"
        normalized.contains("connect") || normalized.contains("init") -> "Соединение устанавливается"
        normalized.contains("active") || normalized.contains("connected") -> "Соединение установлено"
        normalized.contains("end") || normalized.contains("hang") -> "Звонок завершается"
        else -> activeCall.state
    }
}

private fun compactAudioLabel(value: String, maxLength: Int = 28): String {
    val trimmed = value.trim()
    if (trimmed.length <= maxLength) {
        return trimmed
    }
    return trimmed.take(maxLength - 1) + "…"
}