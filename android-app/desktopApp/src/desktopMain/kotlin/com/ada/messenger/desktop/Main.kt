package com.ada.messenger.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ada.messenger.desktop.core.DesktopFileDialogs
import com.ada.messenger.desktop.core.DesktopSessionController
import java.awt.Frame

fun main() = application {
    val controller = remember { DesktopSessionController() }
    val state by controller.state.collectAsState()
    val videoCallState by controller.videoCallState.collectAsState()
    val incomingCall = state.incomingCall
    val incomingCallId = incomingCall?.callId
    val activeVideoCall = state.activeCalls.firstOrNull {
        it.hasVideo && it.groupId == null && it.callId != incomingCallId
    }
    val activeAudioCall = state.activeCalls.firstOrNull {
        !it.hasVideo && it.groupId == null && it.callId != incomingCallId
    }
    val closeWindow = {
        controller.close()
        exitApplication()
    }
    val windowState = rememberWindowState(width = 1280.dp, height = 860.dp)
    val windowIcon = rememberVectorPainter(AdaDesktopLogoVector)

    Window(
        onCloseRequest = closeWindow,
        title = "ADA Desktop",
        state = windowState,
        undecorated = true,
        icon = windowIcon,
    ) {
        DisposableEffect(Unit) {
            // Prevent content clipping on narrow windows
            window.minimumSize = java.awt.Dimension(540, 400)
            onDispose { controller.close() }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            DesktopWindowChrome(
                title = "ADA Desktop",
                onMinimize = {
                    window.extendedState = window.extendedState or Frame.ICONIFIED
                },
                onToggleMaximize = {
                    val isMaximized = window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
                    if (isMaximized) {
                        window.extendedState = Frame.NORMAL
                    } else {
                        // Use screen insets to avoid overlapping the Windows taskbar
                        val insets = java.awt.Toolkit.getDefaultToolkit()
                            .getScreenInsets(window.graphicsConfiguration)
                        val screen = window.graphicsConfiguration.bounds
                        window.extendedState = Frame.NORMAL
                        window.setBounds(
                            screen.x + insets.left,
                            screen.y + insets.top,
                            screen.width - insets.left - insets.right,
                            screen.height - insets.top - insets.bottom,
                        )
                    }
                },
                onClose = closeWindow,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                AdaDesktopApp(
                    state = state,
                    onRegisterFromPattern = controller::createFromPattern,
                    onLoginWithPattern = controller::loginWithPattern,
                    onLoginWithPin = controller::loginWithPin,
                    onUsePatternLogin = controller::switchToPatternLogin,
                    onUsePinLogin = controller::switchToPinLogin,
                    onOpenConversation = controller::openConversation,
                    onImportContact = controller::importContactFromText,
                    onImportContactFromFile = {
                        DesktopFileDialogs.pickTextFile("Import contact payload")
                            ?.let(controller::importContactFromFile)
                    },
                    onImportContactFromQrImage = {
                        DesktopFileDialogs.pickImageFile("Scan contact QR image")
                            ?.let(controller::importContactFromQrImage)
                    },
                    onImportBridgeManifest = controller::importBridgeManifestFromText,
                    onPickBridgeManifestFile = { trustedKey ->
                        DesktopFileDialogs.pickTextFile("Import bridge manifest")
                            ?.let { path -> controller.importBridgeManifestFromFile(path, trustedKey) }
                    },
                    onPickBridgeManifestQrImage = { trustedKey ->
                        DesktopFileDialogs.pickImageFile("Scan bridge manifest QR image")
                            ?.let { path -> controller.importBridgeManifestFromQrImage(path, trustedKey) }
                    },
                    onImportCustomBridgeBootstrap = controller::importCustomBridgeBootstrap,
                    onAddBridge = controller::addBridge,
                    onSetBridgeMode = controller::setBridgeMode,
                    onDetectCensorship = controller::detectCensorship,
                    onSetConnectionProfile = controller::setConnectionProfile,
                    onSetRelayOnly = controller::setRelayOnly,
                    onAddRelayNode = controller::addRelayNode,
                    onSetThemeMode = controller::setThemeMode,
                    onSetPin = controller::enablePinFromSession,
                    onChangePin = controller::changePinFromSession,
                    onDisablePin = controller::disablePinFromSession,
                    onRequestIdentityExportPreview = controller::requestIdentityExportPreview,
                    onClearIdentityExportPreview = controller::clearIdentityExportPreview,
                    onExportIdentityToFile = {
                        DesktopFileDialogs.pickSaveTextFile(
                            title = "Export ADA identity backup",
                            suggestedFileName = "ada-identity-export.json",
                        )?.let(controller::exportIdentityBackupToFile)
                    },
                    onOpenDataDirectory = controller::openDataDirectory,
                    onOpenLogFile = controller::openLogFile,
                    onRefreshWorkspace = controller::refreshWorkspace,
                    onDraftChange = controller::updateDraft,
                    onSendDraft = controller::sendDraft,
                    onRenameContact = controller::renameContact,
                    onDeleteMessage = controller::deleteMessage,
                    onDeleteMessageForEveryone = controller::deleteMessageForEveryone,
                    onStartAudioCall = controller::requestAudioCall,
                    onStartVideoCall = controller::requestVideoCall,
                    onAnswerIncomingCall = controller::answerIncomingCall,
                    onDeclineIncomingCall = controller::declineIncomingCall,
                    onHangupActiveCall = controller::hangupActiveCall,
                    onHangupGroupCall = controller::hangupGroupCall,
                    onLogout = controller::logout,
                    onSendAttachment = {
                        DesktopFileDialogs.pickAnyFile("Attach file")
                            ?.let(controller::sendAttachmentFromPath)
                    },
                    onSaveFile = { message ->
                        val fileName = message.text.removePrefix("Файл: ").trim().ifBlank { "file" }
                        DesktopFileDialogs.pickSaveFile(
                            title = "Сохранить файл",
                            suggestedFileName = fileName,
                        )?.let { destPath -> controller.saveFileToPath(message, destPath) }
                    },
                    onClearPatternError = controller::clearPatternError,
                    onClearSendError = controller::clearSendError,
                    onClearActionMessage = controller::clearActionMessage,
                    onStartWifiLink = controller::startWifiLinkServer,
                    onCancelWifiLink = controller::stopWifiLinkServer,
                    onSnapshotPatternSet = controller::createFromSnapshotWithPattern,
                    onNewUser = controller::createNewUser,
                )

                DesktopVideoCallOverlay(
                    activeCall = activeVideoCall,
                    videoState = videoCallState,
                    onSetLocalAudioEnabled = controller::setLocalAudioEnabled,
                    onSetLocalVideoEnabled = controller::setLocalVideoEnabled,
                    onCycleAudioOutput = controller::cycleAudioOutputDevice,
                    onCycleVideoDevice = controller::cycleVideoDevice,
                    onToggleScreenShare = controller::toggleScreenShare,
                    onHangup = {
                        activeVideoCall?.let { call ->
                            controller.hangupActiveCall(call.callId, call.peerId)
                        }
                    },
                )

                DesktopAudioCallOverlay(
                    activeCall = activeAudioCall,
                    audioState = videoCallState,
                    onSetLocalAudioEnabled = controller::setLocalAudioEnabled,
                    onCycleAudioOutput = controller::cycleAudioOutputDevice,
                    onHangup = {
                        activeAudioCall?.let { call ->
                            controller.hangupActiveCall(call.callId, call.peerId)
                        }
                    },
                )

                DesktopIncomingCallOverlay(
                    incomingCall = incomingCall,
                    onAnswer = controller::answerIncomingCall,
                    onDecline = controller::declineIncomingCall,
                )
            }
        }
    }
}

@Composable
private fun WindowScope.DesktopWindowChrome(
    title: String,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    val chromeHeight = 30.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chromeHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF121821), Color(0xFF0C1118)),
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WindowDraggableArea(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chromeHeight)
                        .padding(start = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = rememberVectorPainter(AdaDesktopLogoVector),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    BasicText(
                        text = title,
                        style = TextStyle(
                            color = Color(0xFFE8EDF4),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            letterSpacing = 0.15.sp,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.padding(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WindowControlButton(glyph = WindowControlGlyph.MINIMIZE, onClick = onMinimize)
                WindowControlButton(glyph = WindowControlGlyph.MAXIMIZE, onClick = onToggleMaximize)
                WindowControlButton(
                    glyph = WindowControlGlyph.CLOSE,
                    onClick = onClose,
                    backgroundColor = Color(0x33A92D35),
                    glyphColor = Color(0xFFF5E7E8),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E2732)),
        )
    }
}

private enum class WindowControlGlyph {
    MINIMIZE,
    MAXIMIZE,
    CLOSE,
}

@Composable
private fun WindowControlButton(
    glyph: WindowControlGlyph,
    onClick: () -> Unit,
    backgroundColor: Color = Color(0x172D3844),
    glyphColor: Color = Color(0xFFE6EBF2),
) {
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(22.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val strokeWidth = 1.5.dp.toPx()
            when (glyph) {
                WindowControlGlyph.MINIMIZE -> drawLine(
                    color = glyphColor,
                    start = Offset(size.width * 0.16f, size.height * 0.76f),
                    end = Offset(size.width * 0.84f, size.height * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )

                WindowControlGlyph.MAXIMIZE -> drawRect(
                    color = glyphColor,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
                    size = Size(size.width * 0.64f, size.height * 0.64f),
                    style = Stroke(width = strokeWidth),
                )

                WindowControlGlyph.CLOSE -> {
                    drawLine(
                        color = glyphColor,
                        start = Offset(size.width * 0.2f, size.height * 0.2f),
                        end = Offset(size.width * 0.8f, size.height * 0.8f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = glyphColor,
                        start = Offset(size.width * 0.8f, size.height * 0.2f),
                        end = Offset(size.width * 0.2f, size.height * 0.8f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private val AdaDesktopLogoVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "AdaDesktopLogo",
        defaultWidth = 108.dp,
        defaultHeight = 108.dp,
        viewportWidth = 108f,
        viewportHeight = 108f,
    ).apply {
        // Outer hexagonal badge — from Logo-01_SIGN_WHITE.svg
        // transform: translate(0,500) scale(0.1,-0.1) → x×0.0216, y=108−y×0.0216
        path(
            fill = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFF3F6FA),
                    Color(0xFFC9D3DF),
                    Color(0xFF7F90A5),
                ),
                start = Offset(11f, 10f),
                end = Offset(97f, 94f),
            ),
        ) {
            moveTo(91.584f, 20.974f)
            curveTo(99.36f, 30.694f, 105.862f, 38.858f, 106.013f, 39.096f)
            curveTo(106.337f, 39.55f, 106.79f, 37.454f, 99.317f, 70.416f)
            curveTo(97.546f, 78.322f, 96.055f, 84.78f, 96.055f, 84.802f)
            curveTo(95.926f, 84.845f, 68.04f, 98.258f, 61.754f, 101.304f)
            lineTo(54.065f, 105.019f)
            lineTo(39.506f, 97.999f)
            curveTo(31.493f, 94.154f, 22.075f, 89.618f, 18.554f, 87.934f)
            lineTo(12.161f, 84.845f)
            lineTo(11.232f, 80.827f)
            curveTo(10.735f, 78.602f, 9.223f, 71.971f, 7.884f, 66.096f)
            curveTo(6.523f, 60.221f, 4.601f, 51.818f, 3.607f, 47.455f)
            lineTo(1.793f, 39.506f)
            lineTo(16.265f, 21.362f)
            lineTo(30.758f, 3.24f)
            lineTo(54.086f, 3.262f)
            lineTo(77.436f, 3.262f)
            close()
        }
        // Inner shield element (depth shadow)
        path(fill = SolidColor(Color(0x55282828))) {
            moveTo(51.516f, 26.266f)
            curveTo(44.215f, 26.827f, 35.662f, 28.274f, 29.225f, 30.002f)
            lineTo(28.296f, 30.262f)
            lineTo(28.296f, 32.357f)
            curveTo(28.296f, 39.744f, 29.851f, 49.399f, 32.076f, 55.728f)
            curveTo(33.091f, 58.601f, 35.467f, 63.526f, 36.936f, 65.772f)
            curveTo(40.997f, 71.971f, 46.332f, 76.831f, 52.769f, 80.222f)
            lineTo(54.259f, 80.978f)
            lineTo(55.145f, 80.546f)
            curveTo(59.962f, 78.106f, 65.189f, 73.915f, 68.537f, 69.768f)
            curveTo(72.814f, 64.454f, 76.032f, 57.672f, 77.868f, 50.004f)
            curveTo(79.078f, 44.906f, 79.898f, 38.47f, 79.92f, 33.869f)
            lineTo(79.92f, 31.536f)
            lineTo(77.933f, 30.542f)
            curveTo(74.304f, 28.728f, 69.638f, 27.367f, 64.476f, 26.654f)
            curveTo(62.186f, 26.352f, 53.762f, 26.093f, 51.516f, 26.266f)
            close()
        }
    }.build()
}