package com.ada.messenger.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ada.messenger.desktop.model.DesktopAuthMode
import com.ada.messenger.desktop.model.DesktopChatMessage
import com.ada.messenger.desktop.model.DesktopSessionUiState
import com.ada.messenger.desktop.ui.components.AdaHexBackground
import com.ada.messenger.desktop.ui.screens.DesktopWorkspaceScreen
import com.ada.messenger.desktop.ui.screens.PinLoginScreen
import com.ada.messenger.desktop.ui.screens.PatternLoginScreen
import com.ada.messenger.desktop.ui.screens.PatternRegistrationScreen
import com.ada.messenger.desktop.ui.screens.SnapshotPatternScreen
import com.ada.messenger.desktop.ui.screens.WifiLinkWaitingScreen
import com.ada.messenger.desktop.ui.theme.ADAMessengerDesktopTheme
import com.ada.messenger.desktop.ui.theme.ThemeMode

@Composable
fun AdaDesktopApp(
    state: DesktopSessionUiState,
    onRegisterFromPattern: (ByteArray, String, String?) -> Unit,
    onLoginWithPattern: (ByteArray) -> Unit,
    onLoginWithPin: (String) -> Unit,
    onUsePatternLogin: () -> Unit,
    onUsePinLogin: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onImportContact: (String) -> Boolean,
    onImportContactFromFile: () -> Unit,
    onImportContactFromQrImage: () -> Unit,
    onImportBridgeManifest: (String, String?, String) -> Boolean,
    onPickBridgeManifestFile: (String?) -> Unit,
    onPickBridgeManifestQrImage: (String?) -> Unit,
    onImportCustomBridgeBootstrap: (String, String) -> Boolean,
    onAddBridge: (String) -> Unit,
    onSetBridgeMode: (String) -> Unit,
    onDetectCensorship: () -> Unit,
    onSetConnectionProfile: (String) -> Unit,
    onSetRelayOnly: (Boolean) -> Unit,
    onAddRelayNode: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetPin: suspend (String) -> String?,
    onChangePin: suspend (String, String) -> String?,
    onDisablePin: suspend (String) -> String?,
    onRequestIdentityExportPreview: () -> Unit,
    onClearIdentityExportPreview: () -> Unit,
    onExportIdentityToFile: () -> Unit,
    onOpenDataDirectory: () -> Unit,
    onOpenLogFile: () -> Unit,
    onRefreshWorkspace: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    onRenameContact: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteMessageForEveryone: (String) -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onAnswerIncomingCall: () -> Unit,
    onDeclineIncomingCall: () -> Unit,
    onHangupActiveCall: (String, String) -> Unit,
    onHangupGroupCall: (String) -> Unit,
    onLogout: () -> Unit,
    onSendAttachment: () -> Unit,
    onSaveFile: (DesktopChatMessage) -> Unit,
    onClearPatternError: () -> Unit,
    onClearSendError: () -> Unit,
    onClearActionMessage: () -> Unit,
    onStartWifiLink: () -> Unit,
    onCancelWifiLink: () -> Unit,
    onSnapshotPatternSet: (ByteArray) -> Unit,
    onNewUser: () -> Unit,
) {
    ADAMessengerDesktopTheme(themeMode = state.themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when {
                state.initialized -> DesktopWorkspaceScreen(
                    state = state,
                    onOpenConversation = onOpenConversation,
                    onImportContact = onImportContact,
                    onImportContactFromFile = onImportContactFromFile,
                    onImportContactFromQrImage = onImportContactFromQrImage,
                    onImportBridgeManifest = onImportBridgeManifest,
                    onPickBridgeManifestFile = onPickBridgeManifestFile,
                    onPickBridgeManifestQrImage = onPickBridgeManifestQrImage,
                    onImportCustomBridgeBootstrap = onImportCustomBridgeBootstrap,
                    onAddBridge = onAddBridge,
                    onSetBridgeMode = onSetBridgeMode,
                    onDetectCensorship = onDetectCensorship,
                    onSetConnectionProfile = onSetConnectionProfile,
                    onSetRelayOnly = onSetRelayOnly,
                    onAddRelayNode = onAddRelayNode,
                    onSetThemeMode = onSetThemeMode,
                    onSetPin = onSetPin,
                    onChangePin = onChangePin,
                    onDisablePin = onDisablePin,
                    onRequestIdentityExportPreview = onRequestIdentityExportPreview,
                    onClearIdentityExportPreview = onClearIdentityExportPreview,
                    onExportIdentityToFile = onExportIdentityToFile,
                    onOpenDataDirectory = onOpenDataDirectory,
                    onOpenLogFile = onOpenLogFile,
                    onRefreshWorkspace = onRefreshWorkspace,
                    onDraftChange = onDraftChange,
                    onSendDraft = onSendDraft,
                    onRenameContact = onRenameContact,
                    onDeleteMessage = onDeleteMessage,
                    onDeleteMessageForEveryone = onDeleteMessageForEveryone,
                    onStartAudioCall = onStartAudioCall,
                    onStartVideoCall = onStartVideoCall,
                    onAnswerIncomingCall = onAnswerIncomingCall,
                    onDeclineIncomingCall = onDeclineIncomingCall,
                    onHangupActiveCall = onHangupActiveCall,
                    onHangupGroupCall = onHangupGroupCall,
                    onLogout = onLogout,
                    onSendAttachment = onSendAttachment,
                    onSaveFile = onSaveFile,
                    onClearSendError = onClearSendError,
                    onClearActionMessage = onClearActionMessage,
                )

                else -> AdaHexBackground {
                    when {
                        state.authMode == DesktopAuthMode.WifiLinkWaiting -> WifiLinkWaitingScreen(
                            linkUrl = state.wifiLinkUrl,
                            onCancel = onCancelWifiLink,
                        )

                        state.authMode == DesktopAuthMode.SnapshotPatternSetup -> SnapshotPatternScreen(
                            patternLoading = state.patternLoading,
                            patternError = state.patternError,
                            onSetPattern = onSnapshotPatternSet,
                            onCancel = onCancelWifiLink,
                            onClearError = onClearPatternError,
                        )

                        state.authMode == DesktopAuthMode.PinLogin -> PinLoginScreen(
                            storedDisplayName = state.storedDisplayName,
                            patternLoading = state.patternLoading,
                            patternError = state.patternError,
                            onLogin = onLoginWithPin,
                            onUsePattern = onUsePatternLogin,
                            onClearError = onClearPatternError,
                            onNewUser = onNewUser,
                        )

                        state.authMode == DesktopAuthMode.PatternLogin -> PatternLoginScreen(
                            storedDisplayName = state.storedDisplayName,
                            canUsePin = state.pinEnabled,
                            patternLoading = state.patternLoading,
                            patternError = state.patternError,
                            onLogin = onLoginWithPattern,
                            onUsePin = onUsePinLogin,
                            onClearError = onClearPatternError,
                            onNewUser = onNewUser,
                        )

                        else -> PatternRegistrationScreen(
                            patternLoading = state.patternLoading,
                            patternError = state.patternError,
                            onRegister = onRegisterFromPattern,
                            onClearError = onClearPatternError,
                            onLinkFromPhone = onStartWifiLink,
                        )
                    }
                }
            }
        }
    }
}
