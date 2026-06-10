package com.ada.messenger.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.ada.messenger.desktop.core.DesktopVideoCallUiState
import com.ada.messenger.desktop.model.DesktopActiveCall
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlinx.coroutines.delay

private const val CONTROLS_AUTO_HIDE_DELAY_MS = 2800L
private const val INTERACTION_THROTTLE_MS = 220L

@Composable
fun DesktopVideoCallOverlay(
    activeCall: DesktopActiveCall?,
    videoState: DesktopVideoCallUiState,
    onSetLocalAudioEnabled: (Boolean) -> Unit,
    onSetLocalVideoEnabled: (Boolean) -> Unit,
    onCycleAudioOutput: () -> Unit,
    onCycleVideoDevice: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onHangup: () -> Unit,
) {
    if (activeCall == null) {
        return
    }

    var localPreviewVisible by remember(activeCall.callId) { mutableStateOf(true) }
    var controlsVisible by remember(activeCall.callId) { mutableStateOf(true) }
    var collapsed by remember(activeCall.callId) { mutableStateOf(false) }
    var lastInteractionMs by remember(activeCall.callId) { mutableStateOf(System.currentTimeMillis()) }

    val callSubtitle = formatCallSubtitle(activeCall, videoState)
    val autoHideControls = shouldAutoHideControls(activeCall, collapsed, videoState)
    val remotePlaceholder = if (videoState.remoteVideoAvailable) {
        "Ожидание кадров собеседника"
    } else {
        "Подключаем удаленное видео"
    }
    val localPlaceholder = when {
        !videoState.localVideoAvailable -> "Локальная камера недоступна"
        videoState.isScreenSharing && videoState.localFrame == null -> "Экран запускается"
        !videoState.localVideoEnabled -> "Камера выключена"
        videoState.localFrame != null -> ""
        else -> "Камера запускается"
    }
    val sourceSummary = videoState.activeVideoSourceLabel?.let { label ->
        if (videoState.isScreenSharing) {
            "Источник: экран ${compactLabel(label)}"
        } else {
            "Источник: ${compactLabel(label)}"
        }
    } ?: if (videoState.localVideoAvailable) {
        if (videoState.isScreenSharing) "Источник: экран" else "Источник: камера"
    } else {
        "Источник видео не найден"
    }
    val audioSummary = videoState.activeAudioOutputLabel?.let { "Выход: ${compactLabel(it)}" } ?: "Выход: системный"
    val collapsedFrame = videoState.remoteFrame ?: videoState.localFrame

    val revealControls by rememberUpdatedState(
        newValue = {
            val now = System.currentTimeMillis()
            if (!controlsVisible) {
                controlsVisible = true
            }
            if (!autoHideControls || now - lastInteractionMs >= INTERACTION_THROTTLE_MS) {
                lastInteractionMs = now
            }
        },
    )

    LaunchedEffect(activeCall.callId, autoHideControls, collapsed) {
        if (!autoHideControls || collapsed) {
            controlsVisible = true
        }
    }

    LaunchedEffect(activeCall.callId, autoHideControls, lastInteractionMs, collapsed) {
        if (!autoHideControls || collapsed) {
            return@LaunchedEffect
        }
        delay(CONTROLS_AUTO_HIDE_DELAY_MS)
        val elapsed = System.currentTimeMillis() - lastInteractionMs
        if (elapsed >= CONTROLS_AUTO_HIDE_DELAY_MS) {
            controlsVisible = false
        }
    }

    if (collapsed) {
        Box(modifier = Modifier.fillMaxSize()) {
            CompactCallCard(
                activeCall = activeCall,
                subtitle = callSubtitle,
                image = collapsedFrame,
                placeholder = if (collapsedFrame == null) remotePlaceholder else "",
                localAudioEnabled = videoState.localAudioEnabled,
                localVideoEnabled = videoState.localVideoEnabled,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                onExpand = {
                    collapsed = false
                    controlsVisible = true
                    lastInteractionMs = System.currentTimeMillis()
                },
                onHangup = onHangup,
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02060B)),
    ) {
        VideoFrameSurface(
            image = videoState.remoteFrame,
            placeholder = remotePlaceholder,
            modifier = Modifier.fillMaxSize(),
            scaleMode = VideoScaleMode.Crop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(activeCall.callId) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (
                                event.type == PointerEventType.Move ||
                                    event.type == PointerEventType.Enter ||
                                    event.type == PointerEventType.Press
                            ) {
                                revealControls()
                            }
                        }
                    }
                },
        )

        if (localPreviewVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (controlsVisible) 124.dp else 24.dp, end = 24.dp)
                    .width(244.dp)
                    .height(154.dp)
                    .shadow(24.dp, RoundedCornerShape(24.dp), clip = false)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF08111A))
                    .border(1.dp, Color(0x8030495E), RoundedCornerShape(24.dp)),
            ) {
                VideoFrameSurface(
                    image = videoState.localFrame,
                    placeholder = localPlaceholder,
                    modifier = Modifier.fillMaxSize(),
                    scaleMode = VideoScaleMode.Crop,
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xB3111820))
                        .border(1.dp, Color(0x663A4654), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (videoState.isScreenSharing) "Ваш экран" else "Вы",
                        color = Color(0xFFF2F6FC),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xD902060B), Color.Transparent),
                        ),
                    )
                    .padding(top = 28.dp, bottom = 36.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusChip(
                    text = if (videoState.remoteFrame != null) "Видео активно" else "Прямой видеозвонок",
                    accent = if (videoState.remoteFrame != null) Color(0xFF57D17A) else Color(0xFF7CB7FF),
                )
                Text(
                    text = activeCall.displayName,
                    color = Color(0xFFF6FAFF),
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontSize = 34.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = callSubtitle,
                    color = Color(0xFFB9C7D6),
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(fontSize = 16.sp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfoChip(text = sourceSummary)
                    InfoChip(text = audioSummary)
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE602060B)),
                        ),
                    )
                    .padding(start = 28.dp, end = 28.dp, top = 48.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RoundControlButton(
                        icon = if (videoState.localAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        label = if (videoState.localAudioEnabled) "Микрофон" else "Без звука",
                        active = !videoState.localAudioEnabled,
                        onClick = { onSetLocalAudioEnabled(!videoState.localAudioEnabled) },
                    )
                    RoundControlButton(
                        icon = if (videoState.localVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = if (videoState.localVideoEnabled) "Камера" else "Видео выкл",
                        active = !videoState.localVideoEnabled,
                        enabled = videoState.localVideoAvailable,
                        onClick = { onSetLocalVideoEnabled(!videoState.localVideoEnabled) },
                    )
                    RoundControlButton(
                        icon = Icons.Default.FlipCameraAndroid,
                        label = "Сменить",
                        enabled = videoState.canSwitchCamera,
                        onClick = onCycleVideoDevice,
                    )
                    RoundControlButton(
                        icon = if (videoState.isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                        label = if (videoState.isScreenSharing) "Стоп экран" else "Экран",
                        active = videoState.isScreenSharing,
                        enabled = videoState.canScreenShare,
                        onClick = onToggleScreenShare,
                    )
                    RoundControlButton(
                        icon = Icons.Default.VolumeUp,
                        label = if (videoState.audioOutputCount > 1) "Выход" else "1 выход",
                        enabled = videoState.audioOutputCount > 1,
                        onClick = onCycleAudioOutput,
                    )
                    RoundControlButton(
                        icon = if (localPreviewVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        label = if (localPreviewVisible) "Скрыть себя" else "Показать себя",
                        onClick = { localPreviewVisible = !localPreviewVisible },
                    )
                    RoundControlButton(
                        icon = Icons.Default.CloseFullscreen,
                        label = "Свернуть",
                        onClick = {
                            collapsed = true
                            controlsVisible = true
                        },
                    )
                }

                RoundControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Завершить",
                    destructive = true,
                    onClick = onHangup,
                )
            }
        }
    }
}

@Composable
private fun CompactCallCard(
    activeCall: DesktopActiveCall,
    subtitle: String,
    image: BufferedImage?,
    placeholder: String,
    localAudioEnabled: Boolean,
    localVideoEnabled: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onHangup: () -> Unit,
) {
    val cardShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .width(320.dp)
            .height(188.dp)
            .shadow(28.dp, cardShape, clip = false)
            .clip(cardShape)
            .background(Color(0xFF071019))
            .border(1.dp, Color(0x80314A60), cardShape)
            .clickable(onClick = onExpand),
    ) {
        VideoFrameSurface(
            image = image,
            placeholder = placeholder.ifBlank { "Звонок активен" },
            modifier = Modifier.fillMaxSize(),
            scaleMode = VideoScaleMode.Crop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE1071019)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniStatusChip(text = if (localAudioEnabled) "Микрофон" else "Mute")
                MiniStatusChip(text = if (localVideoEnabled) "Видео" else "Видео выкл")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactIconButton(icon = Icons.Default.OpenInFull, onClick = onExpand)
                CompactIconButton(icon = Icons.Default.CallEnd, destructive = true, onClick = onHangup)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, accent: Color) {
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
private fun InfoChip(text: String) {
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
private fun RoundControlButton(
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
        modifier = Modifier.width(88.dp),
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
private fun CompactIconButton(
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
private fun MiniStatusChip(text: String) {
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

private fun formatCallSubtitle(
    activeCall: DesktopActiveCall,
    videoState: DesktopVideoCallUiState,
): String {
    val state = activeCall.state.trim()
    if (state.isEmpty()) {
        return if (videoState.remoteFrame != null) "Соединение установлено" else "Прямой видеозвонок"
    }

    val normalized = state.lowercase()
    return when {
        normalized.contains("ring") -> if (activeCall.isOutgoing) "Вызываем собеседника" else "Входящий звонок"
        normalized.contains("connect") || normalized.contains("init") -> "Соединение устанавливается"
        normalized.contains("active") || normalized.contains("connected") -> "Соединение установлено"
        normalized.contains("end") || normalized.contains("hang") -> "Звонок завершается"
        else -> state
    }
}

private fun shouldAutoHideControls(
    activeCall: DesktopActiveCall,
    collapsed: Boolean,
    videoState: DesktopVideoCallUiState,
): Boolean {
    if (collapsed) {
        return false
    }
    val state = activeCall.state.lowercase()
    return videoState.remoteFrame != null ||
        videoState.localFrame != null ||
        state.contains("active") ||
        state.contains("connected")
}

private fun compactLabel(value: String, maxLength: Int = 28): String {
    val trimmed = value.trim()
    if (trimmed.length <= maxLength) {
        return trimmed
    }
    return trimmed.take(maxLength - 1) + "…"
}

private enum class VideoScaleMode {
    Fit,
    Crop,
}

@Composable
private fun VideoFrameSurface(
    image: BufferedImage?,
    placeholder: String,
    modifier: Modifier = Modifier,
    scaleMode: VideoScaleMode = VideoScaleMode.Fit,
) {
    val surfaceShape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(surfaceShape)
            .background(Color(0xFF02060B))
            .border(1.dp, Color(0xFF1B2632), surfaceShape),
    ) {
        SwingPanel(
            factory = { VideoCanvasPanel() },
            modifier = Modifier.fillMaxSize(),
            background = Color(0xFF02060B),
            update = { panel ->
                panel.currentImage = image
                panel.scaleMode = scaleMode
                panel.repaint()
            },
        )

        if (image == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x9402060B)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = placeholder,
                    color = Color(0xFF95A5B7),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

private class VideoCanvasPanel : JPanel() {
    var currentImage: BufferedImage? = null
    var scaleMode: VideoScaleMode = VideoScaleMode.Fit

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.color = java.awt.Color(0x02, 0x06, 0x0B)
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            val image = currentImage ?: return
            val widthRatio = width.toDouble() / image.width.toDouble()
            val heightRatio = height.toDouble() / image.height.toDouble()
            val scale = if (scaleMode == VideoScaleMode.Crop) {
                maxOf(widthRatio, heightRatio)
            } else {
                minOf(widthRatio, heightRatio)
            }
            val drawWidth = (image.width * scale).toInt().coerceAtLeast(1)
            val drawHeight = (image.height * scale).toInt().coerceAtLeast(1)
            val drawX = (width - drawWidth) / 2
            val drawY = (height - drawHeight) / 2
            g2.drawImage(image, drawX, drawY, drawWidth, drawHeight, null)
        } finally {
            g2.dispose()
        }
    }
}