package com.ada.messenger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.ada.messenger.R
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.ConversationItem
import com.ada.messenger.ui.components.GroupAvatar
import com.ada.messenger.ui.components.OwnAvatar
import com.ada.messenger.ui.components.PeerAvatar
import com.ada.messenger.ui.components.QrCodeImage
import com.ada.messenger.ui.theme.AdaBrandingStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ada.messenger.core.CallLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MainTab { CHATS, CALLS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: AdaCoreViewModel,
    onChatSelected: (convId: String, displayName: String) -> Unit,
    onOpenBridge: () -> Unit = {},
    onOpenQrScanner: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenCall: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val conversations by viewModel.conversations.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isCleanMode by viewModel.isCleanMode.collectAsState()
    val onlinePeers by viewModel.onlinePeers.collectAsState()
    var showAddDialog   by remember { mutableStateOf(false) }
    var showMyQrDialog  by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var convToDelete    by remember { mutableStateOf<ConversationItem?>(null) }

    // V-22: Search bar state
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val filteredConversations = remember(conversations, searchResults, searchQuery) {
        if (searchQuery.isBlank()) conversations else searchResults
    }

    // V-27: FAB expand animation
    var fabExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // V-40: Skeleton loading state — show shimmer only during initial data fetch
    var isLoading by remember { mutableStateOf(conversations.isEmpty()) }
    LaunchedEffect(Unit) {
        if (conversations.isEmpty()) {
            // Wait until we get a non-empty list OR 4 seconds have passed (data might genuinely be empty)
            val deadline = System.currentTimeMillis() + 4_000L
            while (conversations.isEmpty() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100L)
            }
            isLoading = false
        }
    }

    // Refresh whenever we enter this screen
    LaunchedEffect(Unit) { viewModel.refreshConversations() }

    LaunchedEffect(searchQuery) {
        viewModel.searchConversations(searchQuery)
    }

    // Track which bottom tab is active
    var selectedTab by remember { mutableStateOf(MainTab.CHATS) }
    val callHistory by viewModel.callHistory.collectAsState()

    // Auto-refresh: conversations every 3s while on Chats tab; refresh call history once on Calls tab
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            MainTab.CHATS -> while (true) {
                delay(3000L)
                viewModel.refreshConversations()
            }
            MainTab.CALLS -> viewModel.refreshCallHistory()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_ada_logo),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "ADA",
                            style = AdaBrandingStyle,
                        )
                    }
                },
                actions = {
                    // V-22: Search toggle
                    IconButton(onClick = { searchActive = !searchActive; if (!searchActive) searchQuery = "" }) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search),
                        )
                    }
                    // Show own contact as QR
                    IconButton(onClick = { showMyQrDialog = true }) {
                        Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.cd_my_qr))
                    }
                    IconButton(onClick = onOpenBridge) {
                        Icon(Icons.Default.Security, contentDescription = "Bridge settings")
                    }
                }
            )
        },
        // V-26: Bottom navigation bar
        bottomBar = {
            val bottomNavItemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onBackground,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = selectedTab == MainTab.CHATS,
                    onClick = { selectedTab = MainTab.CHATS },
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_chats), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    alwaysShowLabel = false,
                    colors = bottomNavItemColors,
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.CALLS,
                    onClick = { selectedTab = MainTab.CALLS; viewModel.refreshCallHistory() },
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_calls), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    alwaysShowLabel = false,
                    colors = bottomNavItemColors,
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_title), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    alwaysShowLabel = false,
                    colors = bottomNavItemColors,
                )
            }
        },
        floatingActionButton = {
            // V-27: Expandable FAB — only on Chats tab
            if (selectedTab == MainTab.CHATS) {
                Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                onOpenQrScanner()
                            },
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_qr))
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                showCreateGroupDialog = true
                            },
                        ) {
                            Icon(Icons.Default.Group, contentDescription = stringResource(R.string.cd_new_group))
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                showAddDialog = true
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.cd_new_chat))
                        }
                    }
                }
                FloatingActionButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    fabExpanded = !fabExpanded
                }) {
                    Icon(
                        if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "New Chat",
                    )
                }
            }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // ── Connection health bar (all tabs) ─────────────────────────
            val connectionLevel by viewModel.connectionLevel.collectAsState()
            val lastTransportOutcome by viewModel.lastTransportOutcome.collectAsState()
            com.ada.messenger.ui.components.ConnectionStatusBar(
                level = connectionLevel,
                lastOutcome = lastTransportOutcome,
            )
            // ──────────────────────────────────────────────────────────────
            if (selectedTab == MainTab.CALLS) {
                CallsTabContent(history = callHistory, modifier = Modifier.fillMaxSize())
            } else {
            // V-22: Search bar
            AnimatedVisibility(visible = searchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_clear))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(24.dp),
                )
            }

            if (isLoading && filteredConversations.isEmpty() && searchQuery.isBlank()) {
                // V-40: Skeleton shimmer loading
                ShimmerConversationList()
            } else if (filteredConversations.isEmpty() && searchQuery.isBlank()) {
                // V-37: Animated empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "emptyState")
                    val bounceOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = -12f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "bounce",
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "💬",
                            fontSize = 56.sp,
                            modifier = Modifier.offset(y = bounceOffset.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.main_no_chats),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.main_no_chats_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                // V-20: Wrap in Box for potential future pull-to-refresh
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filteredConversations, key = { it.id }) { conv ->
                            // V-28: Conversation row appear animation
                            val rowAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
                            val rowOffsetY = remember { androidx.compose.animation.core.Animatable(24f) }
                            LaunchedEffect(Unit) {
                                launch { rowAlpha.animateTo(1f, tween(300)) }
                                launch { rowOffsetY.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
                            }
                            // V-21: Swipe-to-archive (delete)
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value != SwipeToDismissBoxValue.Settled) {
                                        convToDelete = conv
                                    }
                                    false // don't auto-dismiss, show dialog instead
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                modifier = Modifier
                                    .animateItemPlacement(tween(300))
                                    .graphicsLayer {
                                        alpha = rowAlpha.value
                                        translationY = rowOffsetY.value
                                    },
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                MaterialTheme.colorScheme.errorContainer,
                                                RoundedCornerShape(16.dp),
                                            )
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = false,
                            ) {
                                ConversationRow(
                                    conv = conv,
                                    isOnline = conv.peerIdB64?.let { peerId -> (peerId in onlinePeers).takeIf { it } },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onChatSelected(conv.id, conv.displayName.ifBlank { conv.id })
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        convToDelete = conv
                                    },
                                )
                            }
                        }
                    }
                }
            }
    } // end Chats tab else branch
        }
    }

    val errorCleanMode = stringResource(R.string.error_clean_mode_no_contacts)
    val errorInvalidLink = stringResource(R.string.error_invalid_contact_link)

    if (showAddDialog) {
        NewChatDialog(
            onDismiss = { showAddDialog = false },
            onScanQr   = { showAddDialog = false; onOpenQrScanner() },
            onCreateGroup = { showAddDialog = false; showCreateGroupDialog = true },
            onConfirm  = { rawContact ->
                if (isCleanMode) {
                    errorCleanMode
                } else if (viewModel.importContactFromText(rawContact)) {
                    showAddDialog = false
                    null
                } else {
                    errorInvalidLink
                }
            }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name ->
                showCreateGroupDialog = false
                viewModel.createGroup(name) { groupIdHex ->
                    if (groupIdHex != null) {
                        onChatSelected("g:$groupIdHex", name)
                    }
                }
            },
        )
    }

    if (showMyQrDialog) {
        val contactJson = remember { viewModel.getContactCardJson() }
        val contactLink = remember(contactJson) { viewModel.generateContactLink() }
        val avatarIndex by viewModel.myAvatarIndex.collectAsState()
        MyQrDialog(
            contactJson = contactJson,
            contactLink = contactLink,
            displayName = viewModel.myDisplayName.collectAsState().value ?: "",
            avatarIndex = avatarIndex,
            onDismiss = { showMyQrDialog = false },
        )
    }

    convToDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { convToDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_chat_title)) },
            text = {
                Text(stringResource(R.string.dialog_delete_chat_text, conv.displayName.ifBlank { conv.id.take(12) + "…" }))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conv.id)
                        convToDelete = null
                    },
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { convToDelete = null }) { Text(stringResource(R.string.button_cancel)) }
            },
        )
    }
}

// ── My QR dialog ───────────────────────────────────────────────────────────────

@Composable
private fun MyQrDialog(
    contactJson: String?,
    displayName: String,
    avatarIndex: Int,
    contactLink: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_my_contact_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OwnAvatar(index = avatarIndex, size = 64.dp)
                if (contactJson != null) {
                    QrCodeImage(
                        content = contactJson,
                        size = 240.dp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.dialog_my_contact_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (contactLink != null) {
                        val shareTitle = stringResource(R.string.dialog_share_contact_chooser)
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, contactLink)
                                }
                                context.startActivity(Intent.createChooser(intent, shareTitle))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.dialog_share_contact_link))
                        }
                    }
                } else {
                    Text(stringResource(R.string.dialog_profile_not_loaded))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_close)) }
        },
    )
}

// ── ConversationRow ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationItem,
    isOnline: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // V-18: Card-style conversation rows — elevated, rounded
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Avatar
            if (conv.isGroup) {
                GroupAvatar(size = 48.dp)
            } else {
                PeerAvatar(peerId = conv.peerIdB64 ?: conv.id, size = 48.dp, isOnline = isOnline)
            }

            // Main content
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Name + time row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = conv.displayName.ifBlank { conv.id.take(12) + "…" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (conv.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = formatTime(conv.lastActivityMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conv.unreadCount > 0)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                // Preview + unread badge row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val displayText = conversationPreviewText(conv)
                    Text(
                        text = displayText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (conv.unreadCount > 0)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // V-19: Unread count pill badge
                    if (conv.unreadCount > 0) {
                        val pulse = rememberInfiniteTransition(label = "badge_pulse")
                        val scale by pulse.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.12f,
                            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "badge_scale",
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .scale(scale)
                                .padding(start = 8.dp)
                                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(50),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── New Chat dialog with QR scan option ───────────────────────────────────────

@Composable
private fun NewChatDialog(
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onCreateGroup: () -> Unit,
    onConfirm: (String) -> String?,
) {
    var contactInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_chat_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = contactInput,
                    onValueChange = {
                        contactInput = it
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.dialog_new_chat_label)) },
                    minLines = 2,
                    maxLines = 4,
                    isError = validationError != null,
                    supportingText = {
                        Text(validationError ?: stringResource(R.string.dialog_new_chat_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dialog_new_chat_scan_qr))
                }
                OutlinedButton(
                    onClick = onCreateGroup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dialog_new_chat_create_group))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { validationError = onConfirm(contactInput) },
                enabled = contactInput.isNotBlank(),
            ) {
                Text(stringResource(R.string.dialog_new_chat_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая группа") },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Название группы") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (groupName.isNotBlank()) onCreate(groupName.trim()) },
                enabled = groupName.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun conversationPreviewText(conv: ConversationItem): String {
    val raw = conv.lastMessage.trim()
    val kind = conv.lastKind.lowercase(Locale.ROOT)
    val mime = conv.lastMimeType.lowercase(Locale.ROOT)
    val rawLower = raw.lowercase(Locale.ROOT)

    if (!conv.hasMessages) return stringResource(R.string.conversation_preview_no_messages)

    val isAudio = mime.startsWith("audio/") || rawLower == "voice" ||
        rawLower.startsWith("voice_") || rawLower.contains("voice_")
    val isImage = mime.startsWith("image/") || rawLower == "image" ||
        rawLower.endsWith(".jpg") || rawLower.endsWith(".jpeg") ||
        rawLower.endsWith(".png") || rawLower.endsWith(".webp")
    val isVideo = mime.startsWith("video/") || rawLower == "video" ||
        rawLower.endsWith(".mp4") || rawLower.endsWith(".mov") || rawLower.endsWith(".mkv")
    val isAttachment = kind == "file" || kind == "blob_ref" || raw.startsWith("📎") || raw.startsWith("рџ")

    return when {
        isAudio -> stringResource(R.string.message_type_voice)
        isImage -> stringResource(R.string.chat_action_photo)
        isVideo -> stringResource(R.string.message_type_video)
        kind == "audio_call" || rawLower == "audio_call" -> stringResource(R.string.chat_action_audio_call)
        kind == "video_call" || rawLower == "video_call" -> stringResource(R.string.chat_action_video_call)
        kind == "call" || rawLower == "call" -> stringResource(R.string.conversation_preview_call)
        kind == "group_call" && rawLower == "group_video_call" -> stringResource(R.string.conversation_preview_group_video_call)
        kind == "group_call" -> stringResource(R.string.conversation_preview_group_audio_call)
        kind == "group_invite" -> stringResource(R.string.conversation_preview_group_invite)
        isAttachment -> raw.removePrefix("📎").trim().takeIf {
            it.isNotBlank() && !it.startsWith("рџ") && it.lowercase(Locale.ROOT) != "file"
        } ?: stringResource(R.string.chat_action_file)
        raw.isNotBlank() && rawLower != "message" -> raw
        else -> stringResource(R.string.conversation_preview_message)
    }
}

private fun formatTime(ms: Long): String {
    if (ms == 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - ms
    return when {
        diff < 60_000L -> "сейчас"
        diff < 3_600_000L -> "${diff / 60_000L}м"
        diff < 86_400_000L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(ms))
    }
}

// V-40: Skeleton shimmer loading placeholder
@Composable
private fun ShimmerConversationList() {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(6) {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Circle avatar placeholder
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmerAlpha),
                        modifier = Modifier.size(42.dp),
                    ) {}
                    Column(modifier = Modifier.weight(1f)) {
                        // Name placeholder
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmerAlpha),
                            modifier = Modifier
                                .height(14.dp)
                                .fillMaxWidth(0.5f),
                        ) {}
                        Spacer(Modifier.height(6.dp))
                        // Message placeholder
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmerAlpha * 0.7f),
                            modifier = Modifier
                                .height(10.dp)
                                .fillMaxWidth(0.8f),
                        ) {}
                    }
                }
            }
        }
    }
}

