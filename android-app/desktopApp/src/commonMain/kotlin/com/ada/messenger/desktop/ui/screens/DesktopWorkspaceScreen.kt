package com.ada.messenger.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ada.messenger.desktop.model.DesktopActiveCall
import com.ada.messenger.desktop.model.DesktopBridgeStatus
import com.ada.messenger.desktop.model.DesktopChatMessage
import com.ada.messenger.desktop.model.DesktopCallLogEntry
import com.ada.messenger.desktop.model.DesktopConversationItem
import com.ada.messenger.desktop.model.DesktopIncomingCall
import com.ada.messenger.desktop.model.DesktopSessionUiState
import com.ada.messenger.desktop.ui.components.ConnectionStatusBar
import com.ada.messenger.desktop.ui.components.DesktopQrCode
import com.ada.messenger.desktop.ui.components.InitialsAvatar
import com.ada.messenger.desktop.ui.components.TypingIndicator
import com.ada.messenger.desktop.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DesktopWorkspaceSection {
    Chats,
    Calls,
    Settings,
}

private enum class DesktopUtilityDialog {
    AddContact,
    MyQr,
    Bridge,
}

private enum class PinDialogMode {
    Set,
    Change,
    Disable,
}

@Composable
fun DesktopWorkspaceScreen(
    state: DesktopSessionUiState,
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
    onLogout: () -> Unit,
    onAnswerIncomingCall: () -> Unit,
    onDeclineIncomingCall: () -> Unit,
    onHangupActiveCall: (String, String) -> Unit,
    onHangupGroupCall: (String) -> Unit,
    onSendAttachment: () -> Unit,
    onSaveFile: (DesktopChatMessage) -> Unit,
    onClearSendError: () -> Unit,
    onClearActionMessage: () -> Unit,
) {
    LaunchedEffect(state.actionMessage) {
        if (!state.actionMessage.isNullOrBlank()) {
            delay(4000)
            onClearActionMessage()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val splitLayout = maxWidth >= 980.dp
        var selectedSection by remember { mutableStateOf(DesktopWorkspaceSection.Chats) }
        var searchQuery by remember { mutableStateOf("") }
        var utilityDialog by remember { mutableStateOf<DesktopUtilityDialog?>(null) }

        LaunchedEffect(state.incomingCall?.callId) {
            if (state.incomingCall != null) {
                selectedSection = DesktopWorkspaceSection.Calls
            }
        }

        val activeConversation = remember(state.conversations, state.activeConversationId) {
            state.conversations.firstOrNull { it.id == state.activeConversationId }
        }
        val filteredConversations = remember(state.conversations, searchQuery) {
            val needle = searchQuery.trim()
            if (needle.isBlank()) {
                state.conversations
            } else {
                state.conversations.filter { conversation ->
                    conversation.displayName.contains(needle, ignoreCase = true) ||
                        conversation.lastMessage.contains(needle, ignoreCase = true)
                }
            }
        }
        val unreadTotal = remember(state.conversations) { state.conversations.sumOf { it.unreadCount } }

        Row(modifier = Modifier.fillMaxSize()) {
            DesktopNavRail(
                selectedSection = selectedSection,
                unreadTotal = unreadTotal,
                onSelectSection = { selectedSection = it },
                onOpenUtility = { utilityDialog = it },
                displayName = state.displayName,
            )
            HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (selectedSection) {
                    DesktopWorkspaceSection.Chats -> ChatWorkspaceSection(
                        state = state,
                        splitLayout = splitLayout,
                        conversations = filteredConversations,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        activeConversation = activeConversation,
                        onOpenConversation = onOpenConversation,
                        onDraftChange = onDraftChange,
                        onSendDraft = onSendDraft,
                        onRenameContact = onRenameContact,
                        onDeleteMessage = onDeleteMessage,
                        onDeleteMessageForEveryone = onDeleteMessageForEveryone,
                        onStartAudioCall = onStartAudioCall,
                        onStartVideoCall = onStartVideoCall,
                        onSendAttachment = onSendAttachment,
                        onSaveFile = onSaveFile,
                        onClearSendError = onClearSendError,
                    )

                    DesktopWorkspaceSection.Calls -> CallsPane(
                        state = state,
                        onRefreshWorkspace = onRefreshWorkspace,
                        onOpenUtility = { utilityDialog = it },
                        onAnswerIncomingCall = onAnswerIncomingCall,
                        onDeclineIncomingCall = onDeclineIncomingCall,
                        onHangupActiveCall = onHangupActiveCall,
                        onHangupGroupCall = onHangupGroupCall,
                    )
                    DesktopWorkspaceSection.Settings -> SettingsPane(
                        state = state,
                        onSelectChats = { selectedSection = DesktopWorkspaceSection.Chats },
                        onOpenUtility = { utilityDialog = it },
                        onSetThemeMode = onSetThemeMode,
                        onRequestIdentityExportPreview = onRequestIdentityExportPreview,
                        onClearIdentityExportPreview = onClearIdentityExportPreview,
                        onExportIdentityToFile = onExportIdentityToFile,
                        onSetConnectionProfile = onSetConnectionProfile,
                        onSetRelayOnly = onSetRelayOnly,
                        onOpenDataDirectory = onOpenDataDirectory,
                        onOpenLogFile = onOpenLogFile,
                        onRefreshWorkspace = onRefreshWorkspace,
                        onSetPin = onSetPin,
                        onChangePin = onChangePin,
                        onDisablePin = onDisablePin,
                        onLogout = onLogout,
                    )
                }
            }
        }

        UtilityDialog(
            dialog = utilityDialog,
            state = state,
            onDismiss = { utilityDialog = null },
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
            onRefreshWorkspace = onRefreshWorkspace,
        )
    }
}

@Composable
private fun DesktopNavRail(
    selectedSection: DesktopWorkspaceSection,
    unreadTotal: Int,
    onSelectSection: (DesktopWorkspaceSection) -> Unit,
    onOpenUtility: (DesktopUtilityDialog) -> Unit,
    displayName: String?,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val navBg = if (isDark) Color(0xFF0D1014) else Color(0xFFE4E9EF)

    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(navBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "ADA",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        NavRailIconItem(
            icon = Icons.Default.ChatBubble,
            label = "Чаты",
            selected = selectedSection == DesktopWorkspaceSection.Chats,
            badgeCount = unreadTotal,
            onClick = { onSelectSection(DesktopWorkspaceSection.Chats) },
        )
        NavRailIconItem(
            icon = Icons.Default.Call,
            label = "Звонки",
            selected = selectedSection == DesktopWorkspaceSection.Calls,
            onClick = { onSelectSection(DesktopWorkspaceSection.Calls) },
        )

        Spacer(modifier = Modifier.weight(1f))

        NavRailIconItem(
            icon = Icons.Outlined.Settings,
            label = "Настройки",
            selected = selectedSection == DesktopWorkspaceSection.Settings,
            onClick = { onSelectSection(DesktopWorkspaceSection.Settings) },
        )

        Box(
            modifier = Modifier
                .padding(bottom = 14.dp, top = 6.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onOpenUtility(DesktopUtilityDialog.MyQr) },
        ) {
            InitialsAvatar(name = displayName ?: "A", size = 40.dp, fontSize = 16.sp)
        }
    }
}

@Composable
private fun NavRailIconItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val itemBg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val iconTint = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(itemBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (badgeCount > 0) {
            BadgedBox(
                badge = {
                    Badge { Text(if (badgeCount > 99) "99+" else badgeCount.toString(), style = MaterialTheme.typography.labelSmall) }
                },
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(24.dp))
            }
        } else {
            Icon(imageVector = icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun WorkspaceBottomBar(
    selectedSection: DesktopWorkspaceSection,
    unreadTotal: Int,
    onSelectSection: (DesktopWorkspaceSection) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
        NavigationBarItem(
            selected = selectedSection == DesktopWorkspaceSection.Chats,
            onClick = { onSelectSection(DesktopWorkspaceSection.Chats) },
            icon = {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(Icons.Default.ChatBubble, contentDescription = null)
                    if (unreadTotal > 0) {
                        Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                            Text(unreadTotal.toString())
                        }
                    }
                }
            },
            label = { Text("Чаты") },
        )
        NavigationBarItem(
            selected = selectedSection == DesktopWorkspaceSection.Calls,
            onClick = { onSelectSection(DesktopWorkspaceSection.Calls) },
            icon = { Icon(Icons.Default.Call, contentDescription = null) },
            label = { Text("Звонки") },
        )
        NavigationBarItem(
            selected = selectedSection == DesktopWorkspaceSection.Settings,
            onClick = { onSelectSection(DesktopWorkspaceSection.Settings) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("Настройки") },
        )
    }
}

@Composable
private fun ChatWorkspaceSection(
    state: DesktopSessionUiState,
    splitLayout: Boolean,
    conversations: List<DesktopConversationItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    activeConversation: DesktopConversationItem?,
    onOpenConversation: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    onRenameContact: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteMessageForEveryone: (String) -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onSendAttachment: () -> Unit,
    onSaveFile: (DesktopChatMessage) -> Unit,
    onClearSendError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusBar(bridgeStatus = state.bridgeStatus)
        if (splitLayout) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ConversationPane(
                state = state,
                conversations = conversations,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
                onOpenConversation = onOpenConversation,
            )
            HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
            MessagePane(
                state = state,
                conversation = activeConversation,
                modifier = Modifier.weight(1f),
                onDraftChange = onDraftChange,
                onSendDraft = onSendDraft,
                onRenameContact = onRenameContact,
                onDeleteMessage = onDeleteMessage,
                onDeleteMessageForEveryone = onDeleteMessageForEveryone,
                onStartAudioCall = onStartAudioCall,
                onStartVideoCall = onStartVideoCall,
                onSendAttachment = onSendAttachment,
                onSaveFile = onSaveFile,
                onClearSendError = onClearSendError,
            )
        }
        } else {
            ConversationPane(
                state = state,
                conversations = conversations,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.44f),
                onOpenConversation = onOpenConversation,
            )
            HorizontalDivider()
            MessagePane(
                state = state,
                conversation = activeConversation,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.56f),
                onDraftChange = onDraftChange,
                onSendDraft = onSendDraft,
                onRenameContact = onRenameContact,
                onDeleteMessage = onDeleteMessage,
                onDeleteMessageForEveryone = onDeleteMessageForEveryone,
                onStartAudioCall = onStartAudioCall,
                onStartVideoCall = onStartVideoCall,
                onSendAttachment = onSendAttachment,
                onSaveFile = onSaveFile,
                onClearSendError = onClearSendError,
            )
        }
    }
}

@Composable
private fun ConversationPane(
    state: DesktopSessionUiState,
    conversations: List<DesktopConversationItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier,
    onOpenConversation: (String) -> Unit,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            DesktopSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (conversations.isEmpty()) {
            EmptyPane(
                title = if (searchQuery.isBlank()) "Нет чатов" else "Ничего не найдено",
                body = if (searchQuery.isBlank()) {
                    "Добавьте контакт через + или обменяйтесь QR."
                } else {
                    "Попробуйте другой запрос."
                },
                modifier = Modifier.weight(1f).padding(12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == state.activeConversationId,
                        onClick = { onOpenConversation(conversation.id) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                        modifier = Modifier.padding(start = 68.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.heightIn(min = 36.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить поиск")
                }
            }
        },
        placeholder = { Text("Поиск по чатам") },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        ),
    )
}

@Composable
private fun QuickActionRow(
    primaryTitle: String,
    primaryBody: String,
    primaryAction: () -> Unit,
    secondaryTitle: String,
    secondaryBody: String,
    secondaryAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickActionTile(
            title = primaryTitle,
            body = primaryBody,
            modifier = Modifier.weight(1f),
            onClick = primaryAction,
        )
        QuickActionTile(
            title = secondaryTitle,
            body = secondaryBody,
            modifier = Modifier.weight(1f),
            onClick = secondaryAction,
        )
    }
}

@Composable
private fun QuickActionTile(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: DesktopConversationItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InitialsAvatar(name = conversation.displayName, size = 44.dp, fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conversation.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (conversation.unreadCount > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(
                            text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Text(
                text = conversation.lastMessage.ifBlank { "Сообщений пока нет" },
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessagePane(
    state: DesktopSessionUiState,
    conversation: DesktopConversationItem?,
    modifier: Modifier,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    onRenameContact: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteMessageForEveryone: (String) -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onSendAttachment: () -> Unit,
    onSaveFile: (DesktopChatMessage) -> Unit,
    onClearSendError: () -> Unit,
) {
    var renameDialogOpen by remember(conversation?.id) { mutableStateOf(false) }
    var renameDraft by remember(conversation?.id) { mutableStateOf(conversation?.displayName.orEmpty()) }
    var pendingDeleteMessage by remember(conversation?.id) { mutableStateOf<DesktopChatMessage?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (!state.actionMessage.isNullOrBlank()) {
            SupportStrip(text = state.actionMessage, isError = state.actionMessageIsError)
        }

        if (conversation == null) {
            EmptyPane(
                title = "Выберите чат",
                body = "Откройте диалог слева или добавьте новый контакт.",
            )
            return
        }

        // Chat header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val callBusy = state.incomingCall != null || state.activeCalls.isNotEmpty()
                InitialsAvatar(
                    name = conversation.displayName,
                    size = 36.dp,
                    fontSize = 14.sp,
                    isOnline = state.bridgeStatus?.irohReady,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = conversation.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            conversation.isGroup -> "Групповой диалог"
                            state.callAvailability?.available == true -> "Звонки доступны"
                            else -> "Контакт"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (conversation.isDirect) {
                    IconButton(onClick = { renameDialogOpen = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Переименовать", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onStartAudioCall, enabled = !callBusy) {
                        Icon(Icons.Default.Call, contentDescription = "Голосовой звонок")
                    }
                    IconButton(onClick = onStartVideoCall, enabled = !callBusy) {
                        Icon(Icons.Default.Videocam, contentDescription = "Видеозвонок")
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        val listState = rememberLazyListState()
        LaunchedEffect(state.messages.size, conversation.id) {
            if (state.messages.isNotEmpty()) {
                listState.scrollToItem(state.messages.lastIndex)
            }
        }

        if (state.messages.isEmpty()) {
            EmptyPane(
                title = "Сообщений пока нет",
                body = "Отправьте первое сообщение в этом диалоге.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isDownloadable = !message.isMine &&
                            (message.kind == "file" || message.kind == "blob_ref") &&
                            message.fileId in state.downloadableFileIds,
                        onSaveFile = onSaveFile,
                        onRequestDelete = { pendingDeleteMessage = it },
                    )
                }
            }
        }

        if (!state.sendError.isNullOrBlank()) {
            SupportStrip(text = state.sendError, isError = true)
            LaunchedEffect(state.sendError) {
                delay(4000)
                onClearSendError()
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            DesktopMessageComposer(
                draft = state.draft,
                onDraftChange = onDraftChange,
                onSendDraft = onSendDraft,
                attachmentEnabled = conversation.isDirect,
                onAttachFile = onSendAttachment,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (renameDialogOpen && conversation.isDirect) {
            RenameContactDialog(
                initialValue = renameDraft,
                onValueChange = { renameDraft = it },
                onDismiss = { renameDialogOpen = false },
                onConfirm = {
                    onRenameContact(conversation.id.removePrefix("d:"), renameDraft)
                    renameDialogOpen = false
                },
            )
        }

        pendingDeleteMessage?.let { message ->
            DeleteMessageDialog(
                canDeleteForEveryone = conversation.isDirect && message.isMine,
                onDismiss = { pendingDeleteMessage = null },
                onDeleteLocal = {
                    onDeleteMessage(message.id)
                    pendingDeleteMessage = null
                },
                onDeleteForEveryone = {
                    onDeleteMessageForEveryone(message.id)
                    pendingDeleteMessage = null
                },
            )
        }
    }
}

@Composable
private fun DesktopMessageComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    attachmentEnabled: Boolean,
    onAttachFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onAttachFile,
                enabled = attachmentEnabled,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = "Прикрепить файл",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSendDraft() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                ),
            )
            FilledIconButton(
                onClick = onSendDraft,
                enabled = draft.isNotBlank(),
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Отправить сообщение",
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: DesktopChatMessage,
    isDownloadable: Boolean,
    onSaveFile: (DesktopChatMessage) -> Unit,
    onRequestDelete: (DesktopChatMessage) -> Unit,
) {
    val bubbleShape = if (message.isMine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val bubbleBg = if (message.isMine) {
        if (isDark) Color(0xFF183A69) else MaterialTheme.colorScheme.primaryContainer
    } else {
        if (isDark) Color(0xFF20262D) else MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isMine) {
        if (isDark) Color(0xFFD9E6FF) else MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metaColor = textColor.copy(alpha = 0.55f)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.isMine) Spacer(modifier = Modifier.width(8.dp))

        Surface(
            modifier = Modifier.widthIn(max = 480.dp),
            shape = bubbleShape,
            color = bubbleBg,
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!message.isMine && message.senderName.isNotBlank()) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (message.kind == "file" || message.kind == "blob_ref") {
                    val isImage = message.mimeType.startsWith("image/")
                    val fileName = message.text.removePrefix("Файл: ").trim().ifBlank { "Файл" }
                    Row(
                        modifier = Modifier
                            .widthIn(min = 180.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isImage) Icons.Default.Image else Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                            if (message.fileSize > 0L) {
                                Text(
                                    text = formatFileSize(message.fileSize),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = metaColor,
                                )
                            }
                        }
                        if (!message.isMine) {
                            TextButton(
                                onClick = { onSaveFile(message) },
                                enabled = isDownloadable,
                            ) {
                                Text(
                                    text = if (message.kind == "blob_ref") "Скачать" else "Сохранить",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTimestamp(message.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                    )
                    if (message.isMine) {
                        Text(
                            text = when (message.status) {
                                "delivered" -> "✓✓"
                                "read" -> "✓✓"
                                else -> "✓"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = { onRequestDelete(message) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Удалить",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(14.dp),
            )
        }

        if (message.isMine) Spacer(modifier = Modifier.width(4.dp))
    }
}

@Composable
private fun RenameContactDialog(
    initialValue: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Имя контакта") },
        text = {
            OutlinedTextField(
                value = initialValue,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Новое имя") },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun DeleteMessageDialog(
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить сообщение?") },
        text = {
            Text(
                if (canDeleteForEveryone) {
                    "Можно удалить сообщение только у себя или отправить удаление у всех участников direct chat."
                } else {
                    "Сообщение будет удалено только локально."
                },
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canDeleteForEveryone) {
                    TextButton(onClick = onDeleteForEveryone) {
                        Text("У всех")
                    }
                }
                TextButton(onClick = onDeleteLocal) {
                    Text("Удалить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun CallsPane(
    state: DesktopSessionUiState,
    onRefreshWorkspace: () -> Unit,
    onOpenUtility: (DesktopUtilityDialog) -> Unit,
    onAnswerIncomingCall: () -> Unit,
    onDeclineIncomingCall: () -> Unit,
    onHangupActiveCall: (String, String) -> Unit,
    onHangupGroupCall: (String) -> Unit,
) {
    val listedActiveCalls = state.activeCalls.filter { it.groupId != null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!state.actionMessage.isNullOrBlank()) {
            SupportStrip(text = state.actionMessage, isError = state.actionMessageIsError)
        }

        SectionCard(
            title = "Voice/video preflight",
            body = buildString {
                val availability = state.callAvailability
                append("Статус: ")
                append(
                    when {
                        availability == null -> "runtime ещё не сообщил call preflight"
                        availability.available -> "маршрут готов для realtime-звонков"
                        else -> "маршрут пока не готов"
                    },
                )
                if (!availability?.detail.isNullOrBlank()) {
                    append("\n")
                    append(availability?.detail)
                }
                if (state.activeCalls.isNotEmpty()) {
                    append("\nАктивных звонков: ")
                    append(state.activeCalls.size)
                }
                if (state.incomingCall != null) {
                    append("\nВходящий: ")
                    append(state.incomingCall.displayName)
                }
            },
        )

        if (listedActiveCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listedActiveCalls.forEach { activeCall ->
                    ActiveCallCard(
                        activeCall = activeCall,
                        onHangupActiveCall = onHangupActiveCall,
                        onHangupGroupCall = onHangupGroupCall,
                    )
                }
            }
        }

        SectionCard(
            title = "История вызовов",
            body = buildString {
                append("Записей: ")
                append(state.callHistory.size)
                append("\nЗвонки: ")
                append(if (state.bridgeStatus?.capabilities?.realtimeCalls == true) "доступны" else "недоступны")
                append("\nМаршрут: ")
                append(state.bridgeStatus?.lastRoute ?: "нет данных")
            },
        )
        SectionCard(
            title = "Текущее состояние runtime",
            body = buildString {
                append("Профиль: ")
                append(state.displayName ?: "не загружен")
                append("\nPeer: ")
                append(state.peerId?.let(::shortId) ?: "нет")
                append("\nBridge: ")
                append(if (state.bridgeStatus?.hasWorking == true) "есть рабочий маршрут" else "маршрут не подтверждён")
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionTile(
                title = "Bridge",
                body = "Профиль сети",
                modifier = Modifier.weight(1f),
                onClick = { onOpenUtility(DesktopUtilityDialog.Bridge) },
            )
            QuickActionTile(
                title = "Обновить",
                body = "Обновить состояние",
                modifier = Modifier.weight(1f),
                onClick = onRefreshWorkspace,
            )
        }

        if (state.callHistory.isEmpty()) {
            EmptyPane(
                title = "История вызовов пуста",
                body = "Здесь появятся завершённые звонки.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.callHistory, key = { entry -> entry.callId.ifBlank { entry.peerId + entry.startedAtMs } }) { entry ->
                    CallHistoryRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(
    state: DesktopSessionUiState,
    onSelectChats: () -> Unit,
    onOpenUtility: (DesktopUtilityDialog) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onRequestIdentityExportPreview: () -> Unit,
    onClearIdentityExportPreview: () -> Unit,
    onExportIdentityToFile: () -> Unit,
    onSetConnectionProfile: (String) -> Unit,
    onSetRelayOnly: (Boolean) -> Unit,
    onOpenDataDirectory: () -> Unit,
    onOpenLogFile: () -> Unit,
    onRefreshWorkspace: () -> Unit,
    onSetPin: suspend (String) -> String?,
    onChangePin: suspend (String, String) -> String?,
    onDisablePin: suspend (String) -> String?,
    onLogout: () -> Unit,
) {
    var pinDialogMode by remember { mutableStateOf<PinDialogMode?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!state.actionMessage.isNullOrBlank()) {
            SupportStrip(text = state.actionMessage, isError = state.actionMessageIsError)
        }

        SectionCard(
            title = "Профиль",
            body = buildString {
                append("Имя: ")
                append(state.displayName ?: "не задано")
                append("\nPeer ID: ")
                append(state.peerId?.let(::shortId) ?: "не загружен")
                append("\nДиалогов: ")
                append(state.conversations.size)
                append("\nContact card: ")
                append(if (state.contactCardJson.isNullOrBlank()) "не загружена" else "готова")
            },
        )
        SectionCard(
            title = "Desktop runtime",
            body = buildString {
                append("Тема: ")
                append(themeModeLabel(state.themeMode))
                append("\nCensorship: ")
                append(state.censorshipLevel)
                append("\nПрофиль: ")
                append(profileLabel(state.bridgeStatus?.connectionProfile ?: "auto"))
                append("\nRelay-only: ")
                append(if (state.bridgeStatus?.relayOnly == true) "вкл" else "выкл")
                append("\nStack: ")
                append(state.bridgeStatus?.transportStack ?: "unknown")
                append("\nMailbox: ")
                append(state.bridgeStatus?.bridgeMailboxDepth ?: 0)
            },
        )

        ThemeModeSelector(
            currentThemeMode = state.themeMode,
            onThemeModeSelected = onSetThemeMode,
        )

        ConnectionProfileSelector(
            currentProfile = state.bridgeStatus?.connectionProfile ?: "auto",
            onProfileSelected = onSetConnectionProfile,
        )

        RelayOnlyRow(
            enabled = state.bridgeStatus?.relayOnly == true,
            onToggle = onSetRelayOnly,
        )

        SectionCard(
            title = "Быстрый вход по PIN",
            body = buildString {
                append("Статус: ")
                append(if (state.pinEnabled) "включён" else "выключен")
                append("\nРезервный вход: рисунок")
            },
        )

        if (state.pinEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { pinDialogMode = PinDialogMode.Change },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Изменить PIN")
                }
                OutlinedButton(
                    onClick = { pinDialogMode = PinDialogMode.Disable },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Отключить PIN")
                }
            }
        } else {
            OutlinedButton(
                onClick = { pinDialogMode = PinDialogMode.Set },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Включить PIN")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionTile(
                title = "Мой QR",
                body = "Показать контакт",
                modifier = Modifier.weight(1f),
                onClick = { onOpenUtility(DesktopUtilityDialog.MyQr) },
            )
            QuickActionTile(
                title = "Bridge",
                body = "Открыть сеть",
                modifier = Modifier.weight(1f),
                onClick = { onOpenUtility(DesktopUtilityDialog.Bridge) },
            )
        }

        SectionCard(
            title = "Bridge status",
            body = bridgeSummaryText(state.bridgeStatus),
        )

        SectionCard(
            title = "Storage",
            body = buildString {
                append("Core data dir: ")
                append(state.dataDirPath.ifBlank { "неизвестно" })
                append("\nLog file: ")
                append(state.logFilePath.ifBlank { "неизвестно" })
            },
        )

        SectionCard(
            title = "Recovery & transfer",
            body = buildString {
                append("Просмотр и экспорт переносимого backup профиля.")
                if (!state.identityExportJson.isNullOrBlank()) {
                    append("\nPreview готов к копированию.")
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onRequestIdentityExportPreview, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Показать export")
            }
            OutlinedButton(onClick = onExportIdentityToFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Сохранить backup")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onRefreshWorkspace, modifier = Modifier.weight(1f)) {
                Text("Обновить runtime")
            }
            OutlinedButton(
                onClick = { onOpenUtility(DesktopUtilityDialog.AddContact) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Импорт контакта")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onOpenDataDirectory, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть data dir")
            }
            OutlinedButton(onClick = onOpenLogFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть log")
            }
        }

        SectionCard(
            title = "About",
            body = buildString {
                append("Compose Desktop + native ada_core backend.")
                append("\nLast route: ")
                append(state.bridgeStatus?.lastRoute ?: "нет данных")
                append("\nRealtime calls: ")
                append(if (state.bridgeStatus?.capabilities?.realtimeCalls == true) "доступны" else "недоступны")
            },
        )

        if (state.runtimeMetricsJson.isNotBlank() && state.runtimeMetricsJson != "{}") {
            SectionCard(
                title = "Runtime metrics JSON",
                body = state.runtimeMetricsJson,
            )
        }

        if (!state.identityExportJson.isNullOrBlank()) {
            IdentityExportDialog(
                exportJson = state.identityExportJson,
                onDismiss = onClearIdentityExportPreview,
            )
        }

        pinDialogMode?.let { mode ->
            PinManagementDialog(
                mode = mode,
                onDismiss = { pinDialogMode = null },
                onSetPin = onSetPin,
                onChangePin = onChangePin,
                onDisablePin = onDisablePin,
            )
        }

        OutlinedButton(
            onClick = { showLogoutConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Выйти из профиля", color = MaterialTheme.colorScheme.error)
        }

        TextButton(
            onClick = onSelectChats,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Вернуться к чатам")
        }

        if (showLogoutConfirm) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                title = { Text("Выйти из профиля?") },
                text = { Text("Данные останутся на устройстве. Для входа понадобится рисунок или PIN.") },
                confirmButton = {
                    TextButton(onClick = { showLogoutConfirm = false; onLogout() }) {
                        Text("Выйти", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirm = false }) { Text("Отмена") }
                },
            )
        }
    }
}

@Composable
private fun PinManagementDialog(
    mode: PinDialogMode,
    onDismiss: () -> Unit,
    onSetPin: suspend (String) -> String?,
    onChangePin: suspend (String, String) -> String?,
    onDisablePin: suspend (String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!saving) onDismiss()
        },
        title = {
            Text(
                when (mode) {
                    PinDialogMode.Set -> "Включить PIN"
                    PinDialogMode.Change -> "Изменить PIN"
                    PinDialogMode.Disable -> "Отключить PIN"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (mode) {
                        PinDialogMode.Set -> "PIN сохранится поверх текущего рисунка."
                        PinDialogMode.Change -> "Подтвердите текущий PIN и задайте новый."
                        PinDialogMode.Disable -> "Подтвердите текущий PIN."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (mode != PinDialogMode.Set) {
                    PinDigitsField(
                        value = currentPin,
                        label = "Текущий PIN",
                        onValueChange = {
                            currentPin = it
                            errorMessage = null
                        },
                        imeAction = if (mode == PinDialogMode.Disable) ImeAction.Done else ImeAction.Next,
                    )
                }

                if (mode != PinDialogMode.Disable) {
                    PinDigitsField(
                        value = newPin,
                        label = "Новый PIN",
                        onValueChange = {
                            newPin = it
                            errorMessage = null
                        },
                        imeAction = ImeAction.Next,
                    )
                    PinDigitsField(
                        value = confirmPin,
                        label = "Повторите PIN",
                        onValueChange = {
                            confirmPin = it
                            errorMessage = null
                        },
                        imeAction = ImeAction.Done,
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (saving) return@TextButton
                    scope.launch {
                        val result = when (mode) {
                            PinDialogMode.Set -> {
                                when {
                                    newPin.length != 4 -> "PIN должен быть ровно 4 цифры"
                                    confirmPin != newPin -> "PIN-коды не совпадают"
                                    else -> {
                                        saving = true
                                        onSetPin(newPin)
                                    }
                                }
                            }

                            PinDialogMode.Change -> {
                                when {
                                    currentPin.length != 4 -> "Введите текущий PIN из 4 цифр"
                                    newPin.length != 4 -> "PIN должен быть ровно 4 цифры"
                                    confirmPin != newPin -> "PIN-коды не совпадают"
                                    else -> {
                                        saving = true
                                        onChangePin(currentPin, newPin)
                                    }
                                }
                            }

                            PinDialogMode.Disable -> {
                                if (currentPin.length != 4) {
                                    "Введите текущий PIN из 4 цифр"
                                } else {
                                    saving = true
                                    onDisablePin(currentPin)
                                }
                            }
                        }

                        saving = false
                        if (result == null) {
                            onDismiss()
                        } else {
                            errorMessage = result
                        }
                    }
                },
            ) {
                Text(
                    when {
                        saving -> "Сохраняем..."
                        mode == PinDialogMode.Disable -> "Отключить"
                        else -> "Сохранить"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss,
            ) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun PinDigitsField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { updated ->
            onValueChange(updated.filter(Char::isDigit).take(4))
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = imeAction,
        ),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun SectionCard(
    title: String,
    body: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IncomingCallCard(
    incoming: DesktopIncomingCall,
    onAnswerIncomingCall: () -> Unit,
    onDeclineIncomingCall: () -> Unit,
) {
    val canAnswer = incoming.groupId == null && incoming.callSessionId == null && incoming.offerSdp.isNotBlank()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (incoming.hasVideo) "Входящий видеозвонок" else "Входящий звонок",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = incoming.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (canAnswer) {
                    "Desktop media engine подключён для direct calls. Вызов можно принять или отклонить."
                } else {
                    "Для этого типа вызова desktop пока поддерживает только отклонение."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canAnswer) {
                    TextButton(onClick = onAnswerIncomingCall) {
                        Text("Ответить")
                    }
                }
                TextButton(onClick = onDeclineIncomingCall) {
                    Text("Отклонить")
                }
            }
        }
    }
}

@Composable
private fun ActiveCallCard(
    activeCall: DesktopActiveCall,
    onHangupActiveCall: (String, String) -> Unit,
    onHangupGroupCall: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = activeCall.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = buildString {
                    append(callRuntimeStateLabel(activeCall.state))
                    append(" • ")
                    append(if (activeCall.hasVideo) "видео" else "аудио")
                    append(" • ")
                    append(if (activeCall.isOutgoing) "исходящий" else "входящий")
                    if (activeCall.participants.isNotEmpty()) {
                        append(" • участников: ")
                        append(activeCall.participants.size)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeCall.groupId == null && activeCall.peerId.isNotBlank()) {
                    TextButton(onClick = { onHangupActiveCall(activeCall.callId, activeCall.peerId) }) {
                        Text("Сбросить")
                    }
                    Text(
                        text = "Desktop JVM/WebRTC media engine активен для direct call.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { onHangupGroupCall(activeCall.callSessionId ?: activeCall.callId) }) {
                        Text("Сбросить")
                    }
                }
            }
        }
    }
}

@Composable
private fun UtilityDialog(
    dialog: DesktopUtilityDialog?,
    state: DesktopSessionUiState,
    onDismiss: () -> Unit,
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
    onRefreshWorkspace: () -> Unit,
) {
    when (dialog) {
        DesktopUtilityDialog.AddContact -> AddContactDialog(
            state = state,
            onDismiss = onDismiss,
            onImportContact = onImportContact,
            onImportContactFromFile = onImportContactFromFile,
            onImportContactFromQrImage = onImportContactFromQrImage,
        )

        DesktopUtilityDialog.MyQr -> MyQrDialog(
            state = state,
            onDismiss = onDismiss,
        )

        DesktopUtilityDialog.Bridge -> BridgeDialog(
            state = state,
            onDismiss = onDismiss,
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
            onRefreshWorkspace = onRefreshWorkspace,
        )

        null -> Unit
    }
}

@Composable
private fun AddContactDialog(
    state: DesktopSessionUiState,
    onDismiss: () -> Unit,
    onImportContact: (String) -> Boolean,
    onImportContactFromFile: () -> Unit,
    onImportContactFromQrImage: () -> Unit,
) {
    var rawInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val accepted = onImportContact(rawInput)
                    if (accepted) {
                        onDismiss()
                    } else {
                        localError = state.actionMessage ?: "Невалидная contact card или ada:// ссылка."
                    }
                },
            ) {
                Text("Импортировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
        title = { Text("Добавление контакта") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Вставьте ada:// ссылку, contact card или JSON контакта.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = {
                        rawInput = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Contact payload") },
                    minLines = 4,
                    maxLines = 8,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onImportContactFromFile()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Файл")
                    }
                    OutlinedButton(
                        onClick = {
                            onImportContactFromQrImage()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("QR image")
                    }
                }
                if (!localError.isNullOrBlank()) {
                    SupportStrip(text = localError!!, isError = true)
                }
            }
        },
    )
}

@Composable
private fun MyQrDialog(
    state: DesktopSessionUiState,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
        title = { Text("Мой QR") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.actionMessage.isNullOrBlank()) {
                    SupportStrip(text = state.actionMessage, isError = state.actionMessageIsError)
                }

                if (!state.contactCardJson.isNullOrBlank()) {
                    DesktopQrCode(content = state.contactCardJson, size = 240.dp)
                    Text(
                        text = state.displayName ?: "ADA Messenger",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.peerId?.let(::shortId) ?: "Peer ID недоступен",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Покажите QR другому устройству или скопируйте ссылку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.contactShareLink?.let { link ->
                        SelectionContainer {
                            Text(
                                text = link,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(link)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Скопировать ada:// ссылку")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.contactCardJson))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Скопировать JSON")
                    }
                } else {
                    Text(
                        text = "Contact card недоступна.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@Composable
private fun IdentityExportDialog(
    exportJson: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(exportJson)) }) {
                Text("Скопировать JSON")
            }
        },
        title = { Text("Identity export") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Полный backup профиля. Храните его как секрет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = exportJson,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun BridgeDialog(
    state: DesktopSessionUiState,
    onDismiss: () -> Unit,
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
    onRefreshWorkspace: () -> Unit,
) {
    var relayUrl by remember { mutableStateOf("") }
    var bridgeManifestInput by remember { mutableStateOf("") }
    var manifestTrustedKey by remember(state.customBootstrapPublicKey) { mutableStateOf(state.customBootstrapPublicKey) }
    var customBootstrapUrl by remember(state.customBootstrapUrl) { mutableStateOf(state.customBootstrapUrl) }
    var customBootstrapKey by remember(state.customBootstrapPublicKey) { mutableStateOf(state.customBootstrapPublicKey) }
    var manualBridgeLine by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
        title = { Text("Сеть и bridge") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.actionMessage.isNullOrBlank()) {
                    SupportStrip(text = state.actionMessage, isError = state.actionMessageIsError)
                }

                ConnectionProfileSelector(
                    currentProfile = state.bridgeStatus?.connectionProfile ?: "auto",
                    onProfileSelected = onSetConnectionProfile,
                )

                BridgeModeSelector(
                    currentMode = state.bridgeStatus?.mode ?: "auto",
                    onModeSelected = onSetBridgeMode,
                )

                RelayOnlyRow(
                    enabled = state.bridgeStatus?.relayOnly == true,
                    onToggle = onSetRelayOnly,
                )

                SectionCard(
                    title = "Censorship",
                    body = "Текущий уровень: ${state.censorshipLevel}",
                )

                OutlinedButton(
                    onClick = onDetectCensorship,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Перепроверить censorship level")
                }

                ManifestImportSection(
                    manifestInput = bridgeManifestInput,
                    onManifestInputChange = { bridgeManifestInput = it },
                    trustedKey = manifestTrustedKey,
                    onTrustedKeyChange = { manifestTrustedKey = it },
                    onImportText = {
                        onImportBridgeManifest(
                            bridgeManifestInput,
                            manifestTrustedKey.ifBlank { null },
                            "manual",
                        )
                    },
                    onImportFile = { onPickBridgeManifestFile(manifestTrustedKey.ifBlank { null }) },
                    onImportQrImage = { onPickBridgeManifestQrImage(manifestTrustedKey.ifBlank { null }) },
                )

                CustomBootstrapSection(
                    manifestUrl = customBootstrapUrl,
                    onManifestUrlChange = { customBootstrapUrl = it },
                    trustedKey = customBootstrapKey,
                    onTrustedKeyChange = { customBootstrapKey = it },
                    onImport = { onImportCustomBridgeBootstrap(customBootstrapUrl, customBootstrapKey) },
                )

                AddBridgeSection(
                    bridgeLine = manualBridgeLine,
                    onBridgeLineChange = { manualBridgeLine = it },
                    onAddBridge = {
                        onAddBridge(manualBridgeLine)
                        manualBridgeLine = ""
                    },
                )

                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Новый relay URL") },
                    placeholder = { Text("https://relay.example.com") },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onAddRelayNode(relayUrl)
                            relayUrl = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Добавить relay")
                    }
                    OutlinedButton(
                        onClick = onRefreshWorkspace,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Обновить")
                    }
                }

                SectionCard(
                    title = "Runtime snapshot",
                    body = bridgeSummaryText(state.bridgeStatus),
                )

                BridgeNodesList(bridges = state.bridgeStatus?.bridges.orEmpty())
            }
        },
    )
}

@Composable
private fun ConnectionProfileSelector(
    currentProfile: String,
    onProfileSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Connection profile",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        connectionProfileOptions.forEach { option ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProfileSelected(option.value) },
                shape = RoundedCornerShape(18.dp),
                color = if (currentProfile == option.value) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = currentProfile == option.value,
                        onClick = { onProfileSelected(option.value) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    currentThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Theme mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        themeModeOptions.forEach { option ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeModeSelected(option.mode) },
                shape = RoundedCornerShape(18.dp),
                color = if (currentThemeMode == option.mode) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val icon = when (option.mode) {
                        ThemeMode.SYSTEM -> Icons.Outlined.Palette
                        ThemeMode.LIGHT -> Icons.Outlined.LightMode
                        ThemeMode.DARK -> Icons.Outlined.DarkMode
                    }
                    Icon(icon, contentDescription = null)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(
                        selected = currentThemeMode == option.mode,
                        onClick = { onThemeModeSelected(option.mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BridgeModeSelector(
    currentMode: String,
    onModeSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Bridge mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        bridgeModeOptions.forEach { option ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(option.value) },
                shape = RoundedCornerShape(18.dp),
                color = if (currentMode == option.value) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = currentMode == option.value,
                        onClick = { onModeSelected(option.value) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManifestImportSection(
    manifestInput: String,
    onManifestInputChange: (String) -> Unit,
    trustedKey: String,
    onTrustedKeyChange: (String) -> Unit,
    onImportText: () -> Boolean,
    onImportFile: () -> Unit,
    onImportQrImage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Bridge manifest import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = manifestInput,
            onValueChange = onManifestInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Manifest JSON / URL / ada://bridge-manifest") },
            minLines = 4,
            maxLines = 8,
        )
        OutlinedTextField(
            value = trustedKey,
            onValueChange = onTrustedKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Trusted public key (optional)") },
            minLines = 2,
            maxLines = 3,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onImportText() }, modifier = Modifier.weight(1f), enabled = manifestInput.isNotBlank()) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Импорт текста")
            }
            OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Файл")
            }
        }
        OutlinedButton(onClick = onImportQrImage, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCode, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("QR image")
        }
    }
}

@Composable
private fun CustomBootstrapSection(
    manifestUrl: String,
    onManifestUrlChange: (String) -> Unit,
    trustedKey: String,
    onTrustedKeyChange: (String) -> Unit,
    onImport: () -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Custom bootstrap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = manifestUrl,
            onValueChange = onManifestUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Manifest URL") },
            placeholder = { Text("https://example.com/bridge-manifest.json") },
            singleLine = true,
        )
        OutlinedTextField(
            value = trustedKey,
            onValueChange = onTrustedKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Trusted public key") },
            minLines = 2,
            maxLines = 3,
        )
        OutlinedButton(onClick = { onImport() }, modifier = Modifier.fillMaxWidth(), enabled = manifestUrl.isNotBlank()) {
            Icon(Icons.Outlined.Link, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Импортировать bootstrap")
        }
    }
}

@Composable
private fun AddBridgeSection(
    bridgeLine: String,
    onBridgeLineChange: (String) -> Unit,
    onAddBridge: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manual bridge line", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = bridgeLine,
            onValueChange = onBridgeLineChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bridge line") },
            minLines = 2,
            maxLines = 4,
        )
        OutlinedButton(onClick = onAddBridge, modifier = Modifier.fillMaxWidth(), enabled = bridgeLine.isNotBlank()) {
            Text("Добавить bridge")
        }
    }
}

@Composable
private fun RelayOnlyRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Relay-only routing",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Использовать relay-канал вместо прямого маршрута.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun BridgeNodesList(bridges: List<com.ada.messenger.desktop.model.DesktopBridgeNode>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Configured relays",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (bridges.isEmpty()) {
            Text(
                text = "Список relays пока пуст.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bridges.forEach { bridge ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = bridge.address,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append(if (bridge.protocol.isBlank()) "protocol unknown" else bridge.protocol)
                                append(" · ")
                                append(if (bridge.reachable) "reachable" else "unreachable")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(entry: DesktopCallLogEntry) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.displayName.ifBlank { shortId(entry.peerId) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTimestamp(entry.startedAtMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = buildString {
                    append(callDirectionLabel(entry.direction, entry.hasVideo))
                    append(" · ")
                    append(callReasonLabel(entry.reason))
                    append(" · ")
                    append(formatDuration(entry.durationSecs))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.callId.isNotBlank()) {
                Text(
                    text = "Call ID: ${shortId(entry.callId)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyPane(
    title: String,
    body: String,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class ConnectionProfileOption(
    val value: String,
    val label: String,
    val description: String,
)

private data class ThemeModeOption(
    val mode: ThemeMode,
    val label: String,
    val description: String,
)

private data class BridgeModeOption(
    val value: String,
    val label: String,
    val description: String,
)

private val connectionProfileOptions = listOf(
    ConnectionProfileOption("auto", "Auto", "Баланс между прямым iroh, bridge и fallback маршрутами."),
    ConnectionProfileOption("normal", "Normal", "Ставка на прямую связность без агрессивной экономии."),
    ConnectionProfileOption("mobile_saver", "Mobile Saver", "Более щадящий профиль для ограниченной сети и батареи."),
    ConnectionProfileOption("censored_light", "Censored Light", "Умеренная обфускация для частично враждебной сети."),
    ConnectionProfileOption("censored_heavy", "Censored Heavy", "Максимальный упор на bridge/censorship-resilient маршрут."),
    ConnectionProfileOption("allowlist_only", "Allowlist Only", "Только разрешённые/HTTPS-подобные transport пути."),
    ConnectionProfileOption("incident_safe", "Incident Safe", "Аварийный консервативный профиль на случай деградации сети."),
)

private val themeModeOptions = listOf(
    ThemeModeOption(ThemeMode.SYSTEM, "System", "Следовать системной теме Windows/Compose Desktop."),
    ThemeModeOption(ThemeMode.LIGHT, "Light", "Принудительно использовать светлую палитру ADA Desktop."),
    ThemeModeOption(ThemeMode.DARK, "Dark", "Принудительно использовать тёмную палитру ADA Desktop."),
)

private val bridgeModeOptions = listOf(
    BridgeModeOption("auto", "Auto", "Автовыбор уровня маскировки и транспорта."),
    BridgeModeOption("none", "None", "Без дополнительной обфускации поверх базового транспорта."),
    BridgeModeOption("padding", "Padding", "Добавлять padding для маскировки размера пакетов."),
    BridgeModeOption("shaping", "Shaping", "Сглаживать traffic profile под обычный HTTPS-like поток."),
    BridgeModeOption("websocket", "WebSocket", "Предпочитать WebSocket-compatible bridge delivery."),
    BridgeModeOption("fronting", "Fronting", "Использовать domain-fronting oriented bridge path."),
)

private fun bridgeSummaryText(status: DesktopBridgeStatus?): String {
    if (status == null) {
        return "Bridge snapshot ещё не получен от native core."
    }

    return buildString {
        append("Mode: ")
        append(status.mode)
        append("\nConnection profile: ")
        append(profileLabel(status.connectionProfile))
        append("\nWorking route: ")
        append(if (status.hasWorking) "есть" else "нет")
        append("\nIroh ready: ")
        append(if (status.irohReady) "да" else "нет")
        append("\nTransport stack: ")
        append(status.transportStack)
        append("\nRelay-only scope: ")
        append(status.relayOnlyScope)
        append("\nListener route: ")
        append(status.bridgeListenerRoute ?: "нет")
        append("\nBridges: ")
        append(status.bridges.size)
        append("\nManifest source: ")
        append(status.manifestSource ?: "нет")
    }
}

private fun profileLabel(profile: String): String =
    connectionProfileOptions.firstOrNull { it.value == profile }?.label ?: "Auto"

private fun themeModeLabel(themeMode: ThemeMode): String =
    themeModeOptions.firstOrNull { it.mode == themeMode }?.label ?: "System"

private fun callDirectionLabel(direction: String, hasVideo: Boolean): String = when (direction) {
    "outgoing" -> if (hasVideo) "Исходящий видеозвонок" else "Исходящий звонок"
    else -> if (hasVideo) "Входящий видеозвонок" else "Входящий звонок"
}

private fun callRuntimeStateLabel(state: String): String = when (state) {
    "Ringing" -> "Дозвон"
    "IncomingRinging" -> "Входящий вызов"
    "Connecting" -> "Соединение"
    "Active" -> "Активен"
    "Ended" -> "Завершён"
    "Failed" -> "Ошибка"
    else -> state
}

private fun callReasonLabel(reason: String): String = when (reason) {
    "rejected" -> "Отклонён"
    "timeout" -> "Таймаут"
    "missed" -> "Пропущен"
    else -> "Завершён"
}

private fun formatDuration(durationSecs: Long): String {
    if (durationSecs <= 0L) return "0 с"
    val minutes = durationSecs / 60L
    val seconds = durationSecs % 60L
    return if (minutes > 0L) {
        minutes.toString() + " мин " + seconds.toString() + " с"
    } else {
        seconds.toString() + " с"
    }
}

private fun shortId(value: String): String =
    if (value.length <= 12) value else value.take(8) + "…" + value.takeLast(4)

private fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val totalMinutes = (timestampMs / 60000L) % (24L * 60L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return hours.toString().padStart(2, '0') + ":" + minutes.toString().padStart(2, '0')
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GB"
    }
}