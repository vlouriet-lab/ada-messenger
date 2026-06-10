package com.ada.messenger.ui.screens

import kotlinx.coroutines.isActive
import android.Manifest
import com.ada.messenger.ui.components.ChatPattern
import com.ada.messenger.ui.components.chatBackgroundPattern
import com.ada.messenger.ui.components.NewChatInputBar
import com.ada.messenger.ui.components.MediaMode
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.ChatMessage
import com.ada.messenger.core.ConversationItem
import com.ada.messenger.R
import com.ada.messenger.ui.components.MediaRecorderHelper
import com.ada.messenger.ui.components.PeerAvatar
import com.ada.messenger.ui.components.QrCodeImage
import com.ada.messenger.ui.components.TypingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class OpenImagePayload(
    val localPath: String,
    val fileName: String,
)

private data class PreparedAttachment(
    val fileName: String,
    val mimeType: String,
    val localPath: String,
)

// Matches URLs in plain text messages:
// 1. Explicit schemes: https://, http://, ada://
// 2. Bare domains: www.example.com, example.com/path, sub.domain.org:8080/path
//    Must end with a known TLD to avoid false positives on "file.txt", "v1.0" etc.
private val URL_REGEX = Regex(
    """(?:(?:https?|ada)://[^\s<>"'()\[\]]{1,2000}""" +
    """|(?:(?:www\.)?[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.(?:com|org|net|io|dev|ru|me|co|info|biz|app|pro|uk|de|fr|es|it|nl|eu|tv|cc|gg|xyz|site|online|tech|store|su|рф)(?:[/:?#][^\s<>"'()\[\]]{0,2000})?))""",
    RegexOption.IGNORE_CASE,
)

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            }
    }.getOrNull()
}

private fun createOutboundAttachmentFile(context: Context, fileName: String): File? {
    val safeName = File(fileName).name.ifBlank { "attachment" }
    val dir = File(context.cacheDir, "attachments/outbound")
    if (!dir.exists() && !dir.mkdirs()) return null
    return File(dir, "${System.currentTimeMillis()}_$safeName")
}

private suspend fun stageAttachmentFromUri(context: Context, uri: Uri, fileName: String): String? {
    return withContext(Dispatchers.IO) {
        val dest = createOutboundAttachmentFile(context, fileName) ?: return@withContext null
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        }.getOrElse {
            MediaRecorderHelper.secureDelete(dest)
            false
        }
        if (!copied) {
            MediaRecorderHelper.secureDelete(dest)
            return@withContext null
        }
        dest.absolutePath
    }
}

private suspend fun prepareCompressedImage(context: Context, uri: Uri): PreparedAttachment? {
    return withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: return@withContext null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sampleSize = when {
            maxDim > 4000 -> 4
            maxDim > 2200 -> 2
            else -> 1
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return@withContext null

        val scaled = if (maxOf(bitmap.width, bitmap.height) > 1600) {
            val scale = 1600f / maxOf(bitmap.width, bitmap.height).toFloat()
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap

        val baseName = resolveDisplayName(context, uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "image_${System.currentTimeMillis()}"
        val fileName = "$baseName.jpg"
        val dest = createOutboundAttachmentFile(context, fileName)
        if (dest == null) {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            return@withContext null
        }
        val written = runCatching {
            dest.outputStream().use { output ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
            }
        }.getOrElse {
            MediaRecorderHelper.secureDelete(dest)
            false
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        if (!written) {
            MediaRecorderHelper.secureDelete(dest)
            return@withContext null
        }
        PreparedAttachment(fileName = fileName, mimeType = "image/jpeg", localPath = dest.absolutePath)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    convId: String,
    displayName: String,
    viewModel: AdaCoreViewModel,
    onBack: () -> Unit,
    onStartCall: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenAdaLink: (String) -> Unit = {},
) {
    val messages by viewModel.messages.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val connectionLevel by viewModel.connectionLevel.collectAsState()
    val lastTransportOutcome by viewModel.lastTransportOutcome.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val savedFiles by viewModel.savedFiles.collectAsState()
    val playingVoiceId by viewModel.playingVoiceId.collectAsState()
    val voiceProgress by viewModel.voiceProgress.collectAsState()
    val voiceDurationMs by viewModel.voiceDurationMs.collectAsState()
    val listState = rememberLazyListState()
    val snackbarState = remember { SnackbarHostState() }
    var text by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    var openImage by remember { mutableStateOf<OpenImagePayload?>(null) }
    var pendingSaveImage by remember { mutableStateOf<OpenImagePayload?>(null) }
    var pendingSaveFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Voice recording
    val context = LocalContext.current
    val recorderHelper = remember { MediaRecorderHelper(context) }
    // A-5: Release MediaRecorder resources when leaving the screen
    DisposableEffect(recorderHelper) {
        onDispose { recorderHelper.cancel() }
    }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    // Message whose context menu is currently open (null = closed)
    var menuMsg by remember { mutableStateOf<ChatMessage?>(null) }
    // V-9: Reply-to state
    var replyToMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMsg by remember { mutableStateOf<ChatMessage?>(null) }
    // Overflow menu and clear chat confirmation
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showRenameContactDialog by remember { mutableStateOf(false) }
    var contactAliasDraft by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var showIncognitoDialog by remember { mutableStateOf(false) }
    var incognitoQrJson by remember { mutableStateOf<String?>(null) }
    val chatPrefs = remember { context.getSharedPreferences("ada_chat_prefs", android.content.Context.MODE_PRIVATE) }
    var chatPattern by remember { mutableStateOf(ChatPattern.entries.getOrElse(chatPrefs.getInt("chat_pattern_$convId", 0)) { ChatPattern.SOLID }) }
    val scope = rememberCoroutineScope()
    // V-36: Haptic feedback
    val haptic = LocalHapticFeedback.current

    // Peer id (for Direct conversations only, used by "delete for everyone")
    val peerIdB64 = if (convId.startsWith("d:")) convId.removePrefix("d:") else null
    val currentDisplayName = conversations.firstOrNull { it.id == convId }?.displayName
        ?.takeIf { it.isNotBlank() }
        ?: displayName

    // Online presence
    val onlinePeers by viewModel.onlinePeers.collectAsState()
    val isPeerOnline = peerIdB64?.let { it in onlinePeers }

    var pendingCallPeer by remember { mutableStateOf<String?>(null) }
    var pendingVideoCallPeer by remember { mutableStateOf<String?>(null) }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCallPeer?.let { b64 ->
                viewModel.startAudioCall(b64) {}
                onStartCall()
            }
        }
        pendingCallPeer = null
    }

    // For video calls — request both MIC and CAMERA together
    val videoPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true
        val camOk = results[Manifest.permission.CAMERA] == true
        if (micOk && camOk) {
            pendingVideoCallPeer?.let { b64 ->
                viewModel.startVideoCall(b64) {}
                onStartCall()
            }
        }
        pendingVideoCallPeer = null
    }

    // ── Attachment launchers ──────────────────────────────────────────────

    // Photo picker — uses system Photo Picker (Android 13+ PickVisualMedia).
    // PickVisualMedia works through MediaStore and does NOT depend on the
    // Activity being in RESUMED state, which avoids the race condition where
    // ModalBottomSheet dismissal animation briefly drops the Activity from
    // foreground, causing ACTION_OPEN_DOCUMENT to return null immediately.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val prepared = prepareCompressedImage(context, uri) ?: return@launch
                viewModel.sendAttachment(convId, prepared.fileName, prepared.mimeType, prepared.localPath)
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "imagePicker read failed: ${e.message}")
            }
        }
    }

    // Video picker — system Photo Picker, video only
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val rawName = resolveDisplayName(context, uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: "video_${System.currentTimeMillis()}"
                val mimeType = context.contentResolver.getType(uri)
                    ?.takeIf { it.startsWith("video/") }
                    ?: "video/mp4"
                val fileName = if (rawName.contains('.')) rawName else "$rawName.mp4"
                val localPath = stageAttachmentFromUri(context, uri, fileName) ?: return@launch
                viewModel.sendAttachment(convId, fileName, mimeType, localPath)
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "videoPicker read failed: ${e.message}")
            }
        }
    }

    // Any-file picker — streams the selected file via a staged cache copy
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val rawName = resolveDisplayName(context, uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")
                val fileName = if (rawName.contains('.')) rawName else "$rawName.bin"
                val mimeType = context.contentResolver.getType(uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val localPath = stageAttachmentFromUri(context, uri, fileName) ?: return@launch
                viewModel.sendAttachment(convId, fileName, mimeType, localPath)
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "filePicker read failed: ${e.message}")
            }
        }
    }

    val saveImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        val payload = pendingSaveImage
        pendingSaveImage = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val src = File(payload.localPath)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) {
                    scope.launch { snackbarState.showSnackbar(context.getString(R.string.chat_image_saved)) }
                } else {
                    scope.launch { snackbarState.showSnackbar(context.getString(R.string.chat_image_save_failed)) }
                }
            }
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val (path, _) = pendingSaveFile ?: return@rememberLauncherForActivityResult
        pendingSaveFile = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val src = File(path)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) {
                    scope.launch { snackbarState.showSnackbar(context.getString(R.string.chat_image_saved)) }
                } else {
                    scope.launch { snackbarState.showSnackbar(context.getString(R.string.chat_image_save_failed)) }
                }
            }
        }
    }

    // Mic permission for voice recording
    val micRecordPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecordingVoice = true
            recorderHelper.startVoiceRecording {
                scope.launch { isRecordingVoice = false }
            }
        }
    }

    // Voice recording timer effect
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordingSeconds = 0
            while (isRecordingVoice) {
                delay(1000L)
                recordingSeconds++
                if (recordingSeconds >= 120) {
                    isRecordingVoice = false
                }
            }
        }
    }

    // Show send-error Snackbar
    LaunchedEffect(sendError) {
        val err = sendError ?: return@LaunchedEffect
        snackbarState.showSnackbar(err)
        viewModel.clearSendError()
    }

    // Open/close conversation
    LaunchedEffect(convId) {
        viewModel.openConversation(convId)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.closeConversation() }
    }

    // Build the flat item list with date separators inserted between day groups (M-4)
    // Declared before LaunchedEffects so both can reference listItems.size
    val listItems by remember { derivedStateOf { buildChatListItems(messages) } }

    // Near-bottom detector: only auto-scroll if user is already at the tail
    val isNearBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 3
        }
    }

    // Backup refresh — guarantees live updates even if the event-based path misses a message.
    // Lightweight: reads from Rust's in-memory cache (no disk IO unless cache is cold).
    LaunchedEffect(convId) {
        while (isActive) {
            kotlinx.coroutines.delay(2000L)
            viewModel.refreshChatMessages(convId)
        }
    }

    // Auto-scroll to newest message — only when already at/near the bottom
    LaunchedEffect(listItems.size) {
        if (listItems.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(listItems.size - 1)
        }
    }

    // Group conversations get their own screen.
    if (convId.startsWith("g:")) {
        GroupChatScreen(
            convId = convId,
            displayName = displayName,
            viewModel = viewModel,
            onBack = onBack,
            onStartCall = onStartCall,
            onOpenUrl = onOpenUrl,
            onOpenAdaLink = onOpenAdaLink,
        )
        return
    }

    // M-2: Stable callback refs to avoid per-item lambda allocation
    val stableOnPlayVoice = remember<(String, String) -> Unit>(viewModel) {
        { transferId, path -> viewModel.playVoice(transferId, path) }
    }
    val stableOnStopVoice = remember<() -> Unit>(viewModel) { { viewModel.stopVoice() } }
    val stableOnLongPress = remember<(ChatMessage) -> Unit> { { msg -> menuMsg = msg } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(currentDisplayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isPeerOnline != null) {
                            Text(
                                text = stringResource(
                                    if (isPeerOnline) {
                                        R.string.peer_presence_live
                                    } else {
                                        R.string.peer_presence_not_live
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPeerOnline) {
                                    if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // ── Audio call button ─────────────────────────────────
                    IconButton(onClick = {
                        val b64 = peerIdB64 ?: return@IconButton
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startAudioCall(b64) {}
                            onStartCall()
                        } else {
                            pendingCallPeer = b64
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(Icons.Default.Call, contentDescription = stringResource(R.string.chat_action_audio_call))
                    }
                    // ── Video call button ─────────────────────────────────
                    IconButton(onClick = {
                        val b64 = peerIdB64 ?: return@IconButton
                        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (micOk && camOk) {
                            viewModel.startVideoCall(b64) {}
                            onStartCall()
                        } else {
                            pendingVideoCallPeer = b64
                            val perms = buildList {
                                if (!micOk) add(Manifest.permission.RECORD_AUDIO)
                                if (!camOk) add(Manifest.permission.CAMERA)
                            }
                            videoPermLauncher.launch(perms.toTypedArray())
                        }
                    }) {
                        Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.chat_action_video_call))
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_action_more))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            if (peerIdB64 != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_action_rename_contact)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        contactAliasDraft = viewModel.getContactAlias(peerIdB64)
                                        showRenameContactDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_action_incognito)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val b64 = peerIdB64
                                        scope.launch(Dispatchers.IO) {
                                            val json = viewModel.createIncognitoChat(b64)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                incognitoQrJson = json
                                                if (json != null) showIncognitoDialog = true
                                            }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_action_clear_history)) },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_chat_background)) },
                                onClick = {
                                    showOverflowMenu = false
                                    val next = ChatPattern.entries[(chatPattern.ordinal + 1) % ChatPattern.entries.size]
                                    chatPattern = next
                                    chatPrefs.edit().putInt("chat_pattern_$convId", next.ordinal).apply()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (showRenameContactDialog && peerIdB64 != null) {
            AlertDialog(
                onDismissRequest = { showRenameContactDialog = false },
                title = { Text(stringResource(R.string.dialog_contact_alias_title)) },
                text = {
                    OutlinedTextField(
                        value = contactAliasDraft,
                        onValueChange = { contactAliasDraft = it.take(80) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dialog_contact_alias_label)) },
                        placeholder = { Text(currentDisplayName) },
                        supportingText = { Text(stringResource(R.string.dialog_contact_alias_hint)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setContactAlias(peerIdB64, contactAliasDraft)
                        showRenameContactDialog = false
                    }) {
                        Text(stringResource(R.string.button_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameContactDialog = false }) {
                        Text(stringResource(R.string.button_cancel))
                    }
                },
            )
        }

        Column(modifier = Modifier.padding(padding).fillMaxSize().chatBackgroundPattern(chatPattern)) {
            // ── Connection health bar ─────────────────────────────────────
            com.ada.messenger.ui.components.ConnectionStatusBar(
                level = connectionLevel,
                lastOutcome = lastTransportOutcome,
            )
            // ──────────────────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(listItems, key = { item ->
                    when (item) {
                        is ChatListItem.Separator -> "sep_${item.label}"
                        is ChatListItem.Msg -> item.msg.id
                    }
                }) { item ->
                    when (item) {
                        is ChatListItem.Separator -> {
                            // Animated date separator
                            val sepAlpha = remember { Animatable(0f) }
                            LaunchedEffect(Unit) { sepAlpha.animateTo(1f, tween(400)) }
                            Box(modifier = Modifier
                                .animateItemPlacement(tween(300))
                                .graphicsLayer { alpha = sepAlpha.value }
                            ) {
                                DateSeparator(item.label)
                            }
                        }
                        is ChatListItem.Msg -> {
                            // V-28: Bubble appear animation — subtle scale + fade
                            val bubbleScale = remember { Animatable(0.92f) }
                            val bubbleAlpha = remember { Animatable(0f) }
                            LaunchedEffect(Unit) {
                                launch { bubbleScale.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
                                launch { bubbleAlpha.animateTo(1f, tween(200)) }
                            }
                            // V-9: Swipe-to-reply wrapper
                            val density = LocalDensity.current
                            val threshold = with(density) { 72.dp.toPx() }
                            val offsetX = remember { Animatable(0f) }
                            Box(modifier = Modifier
                                .animateItemPlacement(tween(300))
                                .graphicsLayer {
                                    scaleX = bubbleScale.value
                                    scaleY = bubbleScale.value
                                    alpha = bubbleAlpha.value
                                }
                            ) {
                                // Reply icon hint
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 12.dp)
                                        .alpha((offsetX.value / threshold).coerceIn(0f, 1f)),
                                )
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(offsetX.value.toInt(), 0) }
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures(
                                                onDragEnd = {
                                                    scope.launch {
                                                        if (offsetX.value > threshold * 0.3f) {
                                                            replyToMsg = item.msg
                                                        }
                                                        offsetX.animateTo(0f, tween())
                                                    }
                                                },
                                                onDragCancel = {
                                                    scope.launch { offsetX.animateTo(0f, tween()) }
                                                },
                                                onHorizontalDrag = { _, dragAmount ->
                                                    scope.launch {
                                                        offsetX.snapTo(
                                                            (offsetX.value + dragAmount)
                                                                .coerceIn(0f, threshold),
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                ) {
                                    // V-8: Message bubble content
                                    val transfer = transfers.find { it.id == item.msg.fileId || it.fileName == item.msg.text }

                                    MessageBubble(
                                        msg = item.msg,
                                        transferProgress = transfer?.progress,
                                        savedFiles = savedFiles,
                                        playingVoiceId = playingVoiceId,
                                        voiceProgress = voiceProgress,
                                        voiceDurationMs = voiceDurationMs,
                                        isFirstInGroup = item.isFirstInGroup,
                                        isLastInGroup = item.isLastInGroup,
                                        onPlayVoice = stableOnPlayVoice,
                                        onStopVoice = stableOnStopVoice,
                                        onLongPress = { stableOnLongPress(item.msg) },
                                        onToggleReaction = { emoji ->
                                            viewModel.toggleReaction(convId, item.msg.id, emoji, item.msg.myReactions)
                                        },
                                        onOpenImage = { path, fileName ->
                                            openImage = OpenImagePayload(path, fileName)
                                        },
                                        onOpenUrl = onOpenUrl,
                                        onOpenAdaLink = onOpenAdaLink,
                                        onSaveFile = { path, name ->
                                            pendingSaveFile = path to name
                                            saveFileLauncher.launch(name)
                                        },
                                        getDisplayPath = viewModel::getAttachmentForDisplay,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // V-9: Reply-to preview bar
            AnimatedVisibility(visible = replyToMsg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (replyToMsg?.isMine == true) stringResource(R.string.chat_reply_you) else (replyToMsg?.senderName ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = replyToMsg?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { replyToMsg = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            // Validation and visual states for reply/edit
            AnimatedVisibility(visible = replyToMsg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (replyToMsg?.isMine == true) stringResource(R.string.chat_reply_you) else (replyToMsg?.senderName ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = replyToMsg?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { replyToMsg = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            AnimatedVisibility(visible = editingMsg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_editing_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = editingMsg?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { editingMsg = null; text = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            // V-11: Typing indicator
            val peerTyping by viewModel.peerTyping.collectAsState()
            AnimatedVisibility(visible = peerTyping) {
                TypingIndicator()
            }
            HorizontalDivider()
            NewChatInputBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                text = text,
                onTextChanged = { text = it },
                onSendText = { ttl ->
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        text = ""
                        val editing = editingMsg
                        editingMsg = null
                        val reply = replyToMsg
                        replyToMsg = null
                        if (editing != null) {
                            viewModel.editMessage(convId, editing.id, t)
                        } else if (reply != null) {
                            viewModel.sendReply(convId, t, reply.id)
                        } else {
                            viewModel.sendText(convId, t, ttl)
                        }
                    }
                },
                onAttach = { showAttachSheet = true },
                onSendMedia = { file, mode ->
                    if (mode == MediaMode.VOICE) {
                        viewModel.sendAttachment(convId, file.name, "audio/ogg", file.absolutePath)
                    } else {
                        viewModel.sendAttachment(convId, file.name, "video/mp4", file.absolutePath)
                    }
                },
                recorderHelper = recorderHelper
            )
        }

        // Context menu for long-pressed message
        if (menuMsg != null) {
            val m = menuMsg!!
            AlertDialog(
                onDismissRequest = { menuMsg = null },
                confirmButton = {},
                title = { Text(stringResource(R.string.dialog_message_title)) },
                text = {
                    Column {
                        // V-12: Quick emoji reactions (toggle)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            val myReactions = m.myReactions
                            listOf("👍", "❤️", "😂", "😮", "😢", "🔥").forEach { emoji ->
                                val isActive = emoji in myReactions
                                val canAdd = isActive || myReactions.size < 3
                                TextButton(
                                    onClick = {
                                        viewModel.toggleReaction(convId, m.id, emoji, myReactions)
                                        menuMsg = null
                                    },
                                    enabled = canAdd,
                                ) {
                                    Text(
                                        emoji,
                                        fontSize = 24.sp,
                                        modifier = if (isActive) Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .padding(horizontal = 4.dp)
                                        else Modifier,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                        // V-9: Reply
                        TextButton(
                            onClick = {
                                replyToMsg = m
                                editingMsg = null
                                menuMsg = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.message_action_reply))
                        }
                        if (m.isMine && m.kind == "text") {
                            TextButton(
                                onClick = {
                                    text = m.text
                                    editingMsg = m
                                    replyToMsg = null
                                    menuMsg = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.message_action_edit))
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteMessage(convId, m.id)
                                menuMsg = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.message_action_delete_for_me))
                        }
                        if (m.isMine && peerIdB64 != null) {
                            TextButton(
                                onClick = {
                                    viewModel.deleteMessageForEveryone(convId, peerIdB64, m.id)
                                    menuMsg = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.message_action_delete_for_all))
                            }
                        }
                    }
                },
            )
        }

        // Clear chat confirmation dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(R.string.dialog_clear_confirm_title)) },
                text = { Text(stringResource(R.string.dialog_clear_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearConversation(convId)
                        showClearDialog = false
                    }) {
                        Text(stringResource(R.string.dialog_clear_confirm_button), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(R.string.button_cancel))
                    }
                },
            )
        }

        // Incognito chat QR dialog
        if (showIncognitoDialog && incognitoQrJson != null) {
            AlertDialog(
                onDismissRequest = { showIncognitoDialog = false },
                title = { Text(stringResource(R.string.dialog_incognito_title)) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        QrCodeImage(
                            content = incognitoQrJson!!,
                            size = 240.dp,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Text(
                            text = stringResource(R.string.dialog_incognito_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showIncognitoDialog = false }) { Text(stringResource(R.string.button_close)) }
                },
            )
        }

        // Attachment bottom sheet
        if (showAttachSheet) {
            AttachmentBottomSheet(
                onDismiss = { showAttachSheet = false },
                onPickImage = {
                    showAttachSheet = false
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPickVideo = {
                    showAttachSheet = false
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onPickFile = {
                    showAttachSheet = false
                    scope.launch {
                        delay(150)
                        filePicker.launch(arrayOf("*/*"))
                    }
                },
            )
        }

        if (openImage != null) {
            val payload = openImage!!
            Dialog(onDismissRequest = { openImage = null }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(File(payload.localPath))
                                .crossfade(true)
                                .build(),
                            contentDescription = payload.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 560.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                        Text(
                            text = payload.fileName.removePrefix("📎 "),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { openImage = null }) {
                                Text(stringResource(R.string.button_close))
                            }
                            TextButton(onClick = {
                                pendingSaveImage = payload
                                saveImageLauncher.launch(payload.fileName.removePrefix("📎 "))
                            }) {
                                Text(stringResource(R.string.button_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Attachment bottom sheet ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentBottomSheet(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.chat_action_attach),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AttachOption(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.chat_action_photo),
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f)
                )
                AttachOption(
                    icon = Icons.Default.Videocam,
                    label = stringResource(R.string.chat_action_video_attach),
                    onClick = onPickVideo,
                    modifier = Modifier.weight(1f)
                )
                AttachOption(
                    icon = Icons.Default.AttachFile,
                    label = stringResource(R.string.chat_action_file),
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

// ── Voice recording bar ───────────────────────────────────────────────────────

@Composable
private fun VoiceRecordingBar(seconds: Int, onCancel: () -> Unit) {
    val mins = seconds / 60
    val secs = seconds % 60
    val timeStr = "%d:%02d".format(mins, secs)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.chat_recording_indicator) + timeStr,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.button_cancel), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    transferProgress: Float?,
    savedFiles: Map<String, String>,
    playingVoiceId: String?,
    voiceProgress: Float = 0f,
    voiceDurationMs: Int = 0,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    onPlayVoice: (String, String) -> Unit,
    onStopVoice: () -> Unit,
    onLongPress: () -> Unit,
    onOpenImage: (String, String) -> Unit,
    showSenderName: Boolean = false,
    onOpenUrl: (String) -> Unit = {},
    onOpenAdaLink: (String) -> Unit = {},
    onJoinGroupCall: ((String, Boolean) -> Unit)? = null,
    activeGroupCallSessionId: String? = null,
    onSaveFile: ((String, String) -> Unit)? = null,
    getDisplayPath: (suspend (String) -> String?)? = null,
    onToggleReaction: ((String) -> Unit)? = null,
) {
    val alignment = if (msg.isMine) Alignment.End else Alignment.Start
    val isVideoNoteMessage = msg.mimeType.startsWith("video/") && msg.text.startsWith("vidnote_")
    val bubbleColor = if (msg.isMine)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    // V-7: Gradient brush for sender bubbles — diagonal, subtle
    val bubbleBrush = if (msg.isMine && !isVideoNoteMessage) Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.primaryContainer,
        )
    ) else null

    // Look up by fileId first; fall back to bare fileName (the key added by ViewModel)
    val bareFileName = msg.text.removePrefix("📎 ").trim()
    val localPath = savedFiles[msg.fileId]
        ?: savedFiles[bareFileName].also { }
        ?: msg.localPath
    // Decrypt .enc paths to a temp copy for display (received blobs are stored encrypted).
    val displayLocalPath: String? by produceState<String?>(
        initialValue = if (localPath?.endsWith(".enc") != true) localPath else null,
        key1 = localPath,
    ) {
        value = if (localPath != null && getDisplayPath != null) getDisplayPath(localPath) else localPath
    }

    var ttlAlpha by remember { mutableFloatStateOf(1f) }
    var ttlScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(msg.expiresInSecs, msg.timestampMs) {
        msg.expiresInSecs?.let { ttlSecs ->
            val expireTime = msg.timestampMs + (ttlSecs * 1000L)
            while(isActive) {
                val now = System.currentTimeMillis()
                val remaining = expireTime - now
                if (remaining <= 0) {
                    ttlAlpha = 0f
                    ttlScale = 0.8f
                    break
                }
                
                val ttlDuration = ttlSecs * 1000L
                val ratio = remaining.toFloat() / ttlDuration.toFloat()
                
                // Fade out during the last half of the TTL
                if (ratio < 0.5f) {
                    val fadeRatio = ratio * 2f
                    ttlAlpha = fadeRatio.coerceIn(0f, 1f)
                    ttlScale = 0.9f + (0.1f * fadeRatio)
                } else {
                    ttlAlpha = 1f
                    ttlScale = 1f
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(ttlAlpha)
            .scale(ttlScale),
        horizontalAlignment = alignment,
    ) {
        if (showSenderName && !msg.isMine && msg.senderName.isNotBlank()) {
            Text(
                text = msg.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (isVideoNoteMessage) {
            if (msg.replyToId != null && !msg.replyToText.isNullOrEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .padding(
                            start = if (msg.isMine) 6.dp else 2.dp,
                            end = if (msg.isMine) 2.dp else 6.dp,
                            bottom = 6.dp,
                        ),
                ) {
                    Row(modifier = Modifier.padding(8.dp)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = msg.replyToText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = if (msg.isMine) 6.dp else 2.dp, end = if (msg.isMine) 2.dp else 6.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                    ),
            ) {
                VideoNoteBubbleContent(
                    localPath = displayLocalPath,
                    fileName = msg.text,
                    isVideoNote = true,
                )
            }
            MessageMetaRow(
                msg = msg,
                transferProgress = transferProgress,
                modifier = Modifier
                    .width(200.dp)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        } else {
            // V-6: Bubble with tail — refined corner logic + shadow
            val bubbleShape = remember(msg.isMine, isFirstInGroup, isLastInGroup) {
                RoundedCornerShape(
                    topStart    = if (msg.isMine) 18.dp else if (isFirstInGroup) 18.dp else 6.dp,
                    topEnd      = if (!msg.isMine) 18.dp else if (isFirstInGroup) 18.dp else 6.dp,
                    bottomEnd   = if (!msg.isMine) 18.dp else if (isLastInGroup) 4.dp else 6.dp,
                    bottomStart = if (msg.isMine) 18.dp else if (isLastInGroup) 4.dp else 6.dp,
                )
            }
            Box {
                Surface(
                    color = if (bubbleBrush != null) Color.Transparent else bubbleColor,
                    shape = bubbleShape,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(start = if (msg.isMine) 6.dp else 2.dp, end = if (msg.isMine) 2.dp else 6.dp)
                        .then(
                            if (bubbleBrush != null) Modifier.background(
                                brush = bubbleBrush,
                                shape = bubbleShape,
                            ) else Modifier
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress,
                        ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // ── Reply quote ───────────────────────────────────────────
                        if (msg.replyToId != null && !msg.replyToText.isNullOrEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                            ) {
                                Row(modifier = Modifier.padding(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(32.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(2.dp),
                                            ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = msg.replyToText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        when {
                            msg.mimeType.startsWith("image/") -> {
                                ImageBubbleContent(
                                    localPath = displayLocalPath,
                                    fileName = msg.text,
                                    isMine = msg.isMine,
                                    onOpenImage = { path -> onOpenImage(path, msg.text) },
                                )
                            }
                            msg.mimeType.startsWith("audio/") -> {
                                val isPlaying = playingVoiceId == msg.fileId
                                val isVoiceMsg = msg.text.startsWith("voice_") || msg.text.contains("voice", ignoreCase = true)
                                if (isVoiceMsg) {
                                    VoiceBubbleContent(
                                        isPlaying = isPlaying,
                                        progress = if (isPlaying) voiceProgress else 0f,
                                        durationMs = if (isPlaying) voiceDurationMs else 0,
                                        fileSize = msg.fileSize,
                                        isMine = msg.isMine,
                                        localPath = displayLocalPath,
                                        onPlay = {
                                            displayLocalPath?.let { onPlayVoice(msg.fileId, it) }
                                        },
                                        onStop = onStopVoice,
                                    )
                                } else {
                                    AudioFileBubbleContent(
                                        fileName = msg.text,
                                        isPlaying = isPlaying,
                                        progress = if (isPlaying) voiceProgress else 0f,
                                        durationMs = if (isPlaying) voiceDurationMs else 0,
                                        localPath = displayLocalPath,
                                        onPlay = {
                                            displayLocalPath?.let { onPlayVoice(msg.fileId, it) }
                                        },
                                        onStop = onStopVoice,
                                    )
                                }
                            }
                            msg.mimeType.startsWith("video/") -> {
                                VideoNoteBubbleContent(
                                    localPath = displayLocalPath,
                                    fileName = msg.text,
                                    isVideoNote = false,
                                )
                            }
                            msg.kind == "file" || msg.kind == "blob_ref" -> {
                                FileBubbleContent(
                                    fileName = msg.text,
                                    fileSize = msg.fileSize,
                                    isAvailable = displayLocalPath != null,
                                    localPath = displayLocalPath,
                                    onSave = onSaveFile,
                                )
                            }
                            msg.kind == "group_call" && msg.callSessionId != null -> {
                                GroupCallBubbleContent(
                                    hasVideo = msg.callHasVideo,
                                    isActiveRoom = activeGroupCallSessionId == msg.callSessionId,
                                    onJoin = {
                                        onJoinGroupCall?.invoke(msg.callSessionId, msg.callHasVideo)
                                    },
                                )
                            }
                            else -> {
                                // Detect URLs and make them tappable. Clicking shows a
                                // confirmation dialog before navigating to SecureWebViewScreen
                                // (for https://) or the add-contact flow (for ada://).
                                var pendingUrl by remember { mutableStateOf<String?>(null) }

                                if (pendingUrl != null) {
                                    val isAdaLink = pendingUrl!!.startsWith("ada://")
                                    // Normalize bare domain links — add https:// for opening
                                    val normalizedUrl = if (!pendingUrl!!.contains("://")) "https://${ pendingUrl!! }" else pendingUrl!!
                                    AlertDialog(
                                        onDismissRequest = { pendingUrl = null },
                                        title = {
                                            Text(if (isAdaLink) stringResource(R.string.dialog_add_contact_title) else stringResource(R.string.dialog_open_link_title))
                                        },
                                        text = {
                                            Column {
                                                Text(
                                                    text = pendingUrl!!,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                if (!isAdaLink) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = stringResource(R.string.dialog_open_link_warning),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                if (isAdaLink) onOpenAdaLink(normalizedUrl)
                                                else onOpenUrl(normalizedUrl)
                                                pendingUrl = null
                                            }) { Text(if (isAdaLink) stringResource(R.string.dialog_add_contact_button) else stringResource(R.string.dialog_open_link_button)) }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { pendingUrl = null }) {
                                                Text(stringResource(R.string.button_cancel))
                                            }
                                        },
                                    )
                                }

                                val linkColor = MaterialTheme.colorScheme.primary
                                val textColor = MaterialTheme.colorScheme.onSurface
                                val editedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                val editedSuffix = if (msg.isEdited) " ${stringResource(R.string.message_edited_suffix)}" else ""
                                val annotated = remember(msg.text, editedSuffix, linkColor, textColor, editedColor) {
                                    buildAnnotatedString {
                                        var cursor = 0
                                        for (match in URL_REGEX.findAll(msg.text)) {
                                            if (match.range.first > cursor) {
                                                withStyle(SpanStyle(color = textColor)) {
                                                    append(msg.text.substring(cursor, match.range.first))
                                                }
                                            }
                                            pushStringAnnotation("URL", match.value)
                                            withStyle(
                                                SpanStyle(
                                                    color = linkColor,
                                                    textDecoration = TextDecoration.Underline,
                                                ),
                                            ) {
                                                append(match.value)
                                            }
                                            pop()
                                            cursor = match.range.last + 1
                                        }
                                        if (cursor < msg.text.length) {
                                            withStyle(SpanStyle(color = textColor)) {
                                                append(msg.text.substring(cursor))
                                            }
                                        }
                                        if (editedSuffix.isNotEmpty()) {
                                            append(" ")
                                            withStyle(SpanStyle(color = editedColor)) {
                                                append(editedSuffix.trim())
                                            }
                                        }
                                    }
                                }

                                if (annotated.getStringAnnotations("URL", 0, annotated.length).isEmpty()) {
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                } else {
                                    // L-2: ClickableText deprecated since Compose 1.7; keep until BOM upgrade
                                    @Suppress("DEPRECATION")
                                    androidx.compose.foundation.text.ClickableText(
                                        text = annotated,
                                        style = MaterialTheme.typography.bodyMedium,
                                        onClick = { offset ->
                                            annotated
                                                .getStringAnnotations("URL", offset, offset)
                                                .firstOrNull()
                                                ?.let { pendingUrl = it.item }
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        MessageMetaRow(
                            msg = msg,
                            transferProgress = transferProgress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        MessageReactionsRow(msg, onToggleReaction)
    }
}

@Composable
private fun MessageMetaRow(
    msg: ChatMessage,
    transferProgress: Float?,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (transferProgress != null && transferProgress < 1f) {
            CircularProgressIndicator(
                progress = { transferProgress },
                modifier = Modifier.size(10.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${(transferProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        } else if (msg.status == "sending") {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = formatMsgTime(msg.timestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = if (msg.isMine)
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (msg.isMine) {
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = msg.status,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.7f))
                        .togetherWith(fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f))
                },
                label = "status_anim",
            ) { status ->
                Text(
                    text = statusIcon(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == "read") MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MessageReactionsRow(msg: ChatMessage, onToggleReaction: ((String) -> Unit)? = null) {
    if (msg.reactions.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            msg.reactions.forEach { (emoji, count) ->
                val isMine = emoji in msg.myReactions
                Surface(
                    color = if (isMine) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.clickable(enabled = onToggleReaction != null) {
                        onToggleReaction?.invoke(emoji)
                    }
                ) {
                    Text(
                        text = if (count > 1) "$emoji $count" else emoji,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

// ── Chat bubble shape with integrated tail ────────────────────────────────────

/**
 * A WhatsApp/Telegram-style chat bubble shape with a small triangular tail
 * at the bottom-right (outgoing) or bottom-left (incoming).
 * The tail is part of the path — no separate Canvas overlay needed.
 */
private class ChatBubbleShape(private val isMine: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { 16.dp.toPx() }    // corner radius
        val tailW = with(density) { 6.dp.toPx() }  // tail horizontal extent
        val tailH = with(density) { 14.dp.toPx() } // tail vertical extent on edge
        val w = size.width
        val h = size.height

        val path = Path().apply {
            if (isMine) {
                // ── Outgoing: tail on bottom-right ──
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                // Top-right corner
                quadraticTo(w, 0f, w, r)
                // Right edge down to tail attachment
                lineTo(w, h - tailH)
                // Tail curve 1: right edge → tip (cubic, tangent-matched)
                cubicTo(
                    w,                  h,                      // cp1: straight down from edge
                    w + tailW * 1.5f,   h - tailW * 0.15f,     // cp2: swings outward
                    w + tailW,          h,                      // tail tip
                )
                // Tail curve 2: tip → bottom edge (G1-continuous)
                cubicTo(
                    w + tailW * 0.2f,   h + tailW * 0.24f,     // cp1: matched tangent
                    w,                  h,                      // cp2: back at corner
                    w - r * 0.8f,       h,                      // on bottom edge
                )
                // Bottom edge
                lineTo(r, h)
                // Bottom-left corner
                quadraticTo(0f, h, 0f, h - r)
                // Left edge up
                lineTo(0f, r)
                // Top-left corner
                quadraticTo(0f, 0f, r, 0f)
            } else {
                // ── Incoming: tail on bottom-left (mirror) ──
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                // Top-right corner
                quadraticTo(w, 0f, w, r)
                // Right edge down
                lineTo(w, h - r)
                // Bottom-right corner
                quadraticTo(w, h, w - r, h)
                // Bottom edge to tail zone
                lineTo(r * 0.8f, h)
                // Tail curve 1: bottom edge → tip (cubic, tangent-matched)
                cubicTo(
                    0f,                 h,                      // cp1: at bubble corner
                    -tailW * 0.2f,      h + tailW * 0.24f,     // cp2: matched tangent
                    -tailW,             h,                      // tail tip
                )
                // Tail curve 2: tip → left edge (G1-continuous)
                cubicTo(
                    -tailW * 1.5f,      h - tailW * 0.15f,     // cp1: swings outward
                    0f,                 h,                      // cp2: back at corner
                    0f,                 h - tailH,              // on left edge
                )
                // Left edge up
                lineTo(0f, r)
                // Top-left corner
                quadraticTo(0f, 0f, r, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

// ── Media bubble helpers ──────────────────────────────────────────────────────

@Composable
private fun ImageBubbleContent(
    localPath: String?,
    fileName: String,
    isMine: Boolean,
    onOpenImage: (String) -> Unit,
) {
    if (localPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(java.io.File(localPath))
                .crossfade(true)
                .build(),
            contentDescription = fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenImage(localPath) },
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            if (isMine) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(fileName.removePrefix("📎 "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VoiceBubbleContent(
    isPlaying: Boolean,
    progress: Float,
    durationMs: Int,
    fileSize: Long,
    isMine: Boolean,
    localPath: String?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val waveColor = if (isMine)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.secondary
    val waveTrackColor = waveColor.copy(alpha = 0.25f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
    ) {
        // Play/Pause button
        if (localPath != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(waveColor.copy(alpha = 0.15f))
                    .clickable(onClick = if (isPlaying) onStop else onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.audio_action_pause) else stringResource(R.string.audio_action_play),
                    tint = waveColor,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Waveform bars
            WaveformBars(
                progress = progress,
                activeColor = waveColor,
                trackColor = waveTrackColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )
            Spacer(Modifier.height(2.dp))
            // Duration / progress text
            val timeText = if (isPlaying && durationMs > 0) {
                val current = (progress * durationMs).toInt()
                "${formatDurationShort(current)} / ${formatDurationShort(durationMs)}"
            } else if (fileSize > 0L) {
                formatFileSize(fileSize)
            } else {
                stringResource(R.string.message_type_voice)
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Fake waveform visualization — generates deterministic bar heights and shows progress overlay. */
@Composable
private fun WaveformBars(
    progress: Float,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 32,
) {
    // Generate deterministic random-looking bar heights
    val heights = remember {
        val seed = 42
        val bars = FloatArray(barCount)
        var x = seed
        for (i in 0 until barCount) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            bars[i] = 0.15f + 0.85f * ((x % 100) / 100f)
        }
        bars
    }

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height
        val barWidth = totalWidth / (barCount * 2f - 1f)
        val gap = barWidth

        for (i in 0 until barCount) {
            val x = i * (barWidth + gap)
            val barHeight = totalHeight * heights[i]
            val y = (totalHeight - barHeight) / 2f
            val barProgress = (i.toFloat() / barCount)
            val color = if (barProgress <= progress) activeColor else trackColor
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** For displaying non-voice audio files (music, recordings) with playback controls */
@Composable
private fun AudioFileBubbleContent(
    fileName: String,
    isPlaying: Boolean,
    progress: Float,
    durationMs: Int,
    localPath: String?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
    ) {
        if (localPath != null) {
            FilledIconButton(
                onClick = if (isPlaying) onStop else onPlay,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.audio_action_pause) else stringResource(R.string.audio_action_play),
                )
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName.removePrefix("📎 "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isPlaying) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp),
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                )
                if (durationMs > 0) {
                    val current = (progress * durationMs).toInt()
                    Text(
                        text = "${formatDurationShort(current)} / ${formatDurationShort(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatDurationShort(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
private fun VideoNoteBubbleContent(
    localPath: String?,
    @Suppress("UNUSED_PARAMETER") fileName: String,
    isVideoNote: Boolean = false,
) {
    val context = LocalContext.current
    val videoImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
    var isVideoPlaying by remember { mutableStateOf(false) }
    var showVideoDialog by remember { mutableStateOf(false) }

    val shape = if (isVideoNote) CircleShape else RoundedCornerShape(12.dp)
    val sizeModifier = if (isVideoNote) {
        Modifier.size(200.dp)
    } else {
        Modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 200.dp)
    }

    if (showVideoDialog && localPath != null) {
        Dialog(onDismissRequest = { showVideoDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black,
            ) {
                Box(
                    modifier = Modifier
                        .sizeIn(maxWidth = 360.dp, maxHeight = 480.dp)
                        .aspectRatio(1f)
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(localPath)
                                setOnCompletionListener { showVideoDialog = false }
                                setOnErrorListener { _, _, _ ->
                                    showVideoDialog = false
                                    true
                                }
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { showVideoDialog = false },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.button_close),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    Box(
        modifier = sizeModifier
            .clip(shape)
            .background(if (isVideoNote) Color.Black else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (localPath != null) {
            if (isVideoPlaying && !isVideoNote) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(localPath)
                            setOnCompletionListener { isVideoPlaying = false }
                            setOnErrorListener { _, _, _ -> isVideoPlaying = false; true }
                            start()
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(java.io.File(localPath))
                        .crossfade(true)
                        .build(),
                    imageLoader = videoImageLoader,
                    contentDescription = stringResource(R.string.message_type_video),
                    contentScale = if (isVideoNote) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
                // Play button overlay
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    color = Color.Black.copy(alpha = 0.5f),
                    onClick = {
                        if (isVideoNote) showVideoDialog = true else isVideoPlaying = true
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.video_action_watch),
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.message_type_video), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun FileBubbleContent(
    fileName: String,
    fileSize: Long,
    isAvailable: Boolean,
    localPath: String? = null,
    onSave: ((String, String) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            Icons.Default.AttachFile,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(fileName, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (fileSize > 0L) {
                Text(
                    formatFileSize(fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            isAvailable && localPath != null && onSave != null -> {
                IconButton(
                    onClick = { onSave(localPath, fileName) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Сохранить файл",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            !isAvailable -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun GroupCallBubbleContent(
    hasVideo: Boolean,
    isActiveRoom: Boolean,
    onJoin: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (hasVideo) Icons.Default.Videocam else Icons.Default.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasVideo) "Групповой видеозвонок" else "Групповой звонок",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (isActiveRoom) "Комната уже активна на этом устройстве" else "Участники могут подключаться по этому сообщению",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
            Text(if (isActiveRoom) "Открыть звонок" else "Присоединиться")
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

@Composable
private fun DateSeparator(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    modifier: Modifier = Modifier,
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit = {},
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Attach button
        IconButton(onClick = onAttach) {
            Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.chat_action_attach))
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(4.dp))
        // V-10: Animated crossfade between send and mic buttons
        // V-29: Send button bounce animation
        val sendBounce = remember { Animatable(1f) }
        val sendScope = rememberCoroutineScope()
        Crossfade(targetState = text.isNotBlank(), label = "send_mic") { showSend ->
            if (showSend) {
                FilledIconButton(
                    onClick = {
                        sendScope.launch {
                            sendBounce.animateTo(0.75f, tween(60))
                            sendBounce.animateTo(1.12f, tween(100))
                            sendBounce.animateTo(1f, tween(80))
                        }
                        onSend()
                    },
                    modifier = Modifier.graphicsLayer {
                        scaleX = sendBounce.value
                        scaleY = sendBounce.value
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_action_send))
                }
            } else {
                // Mic button — tap & hold to record voice
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onVoiceStart()
                                    tryAwaitRelease()
                                    onVoiceStop()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = stringResource(R.string.chat_action_voice_message),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private fun statusIcon(status: String): String = when (status) {
    "sending" -> "○"
    "sent" -> "✓"
    "delivered" -> "✓✓"
    "read" -> "✓✓"
    "failed" -> "✗"
    else -> ""
}

private fun formatMsgTime(ms: Long): String {
    if (ms == 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}

// ── Add member dialog ─────────────────────────────────────────────────────────

@Composable
private fun AddMemberDialog(
    availableContacts: List<ConversationItem>,
    currentCount: Int,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manualPeerId by remember { mutableStateOf("") }
    val remaining = 16 - currentCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.group_action_add_member))
                Text(
                    stringResource(R.string.group_member_slots) + "$remaining/16",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (availableContacts.isNotEmpty()) {
                    Text(stringResource(R.string.group_contacts_header), style = MaterialTheme.typography.labelMedium)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(availableContacts) { contact ->
                            val peerId = contact.peerIdB64 ?: return@items
                            Surface(
                                onClick = { onAdd(peerId) },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PeerAvatar(peerId = peerId, size = 32.dp)
                                    Text(
                                        text = contact.displayName.ifBlank { peerId.take(12) + "…" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.group_manual_entry),
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.group_manual_input_hint),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedTextField(
                    value = manualPeerId,
                    onValueChange = { manualPeerId = it.trim() },
                    label = { Text("Peer ID (base64)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (manualPeerId.isNotBlank()) onAdd(manualPeerId)
                },
                enabled = manualPeerId.isNotBlank(),
            ) { Text(stringResource(R.string.dialog_add_contact_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

// ── Date separator helpers ────────────────────────────────────────────────────

private sealed class ChatListItem {
    data class Separator(val label: String) : ChatListItem()
    data class Msg(
        val msg: ChatMessage,
        val isFirstInGroup: Boolean = false,
        val isLastInGroup: Boolean = false
    ) : ChatListItem()
}

private fun dayLabel(ms: Long): String {
    if (ms == 0L) return ""
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterday = today - 86_400_000L
    return when {
        ms >= today -> "Сегодня"
        ms >= yesterday -> "Вчера"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

private fun dayKey(ms: Long): String {
    if (ms == 0L) return "0"
    return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(ms))
}

private fun buildChatListItems(messages: List<ChatMessage>): List<ChatListItem> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<ChatListItem>()
    var lastDayKey = ""
    for (i in messages.indices) {
        val msg = messages[i]
        val dk = dayKey(msg.timestampMs)
        if (dk != lastDayKey) {
            result += ChatListItem.Separator(dayLabel(msg.timestampMs))
            lastDayKey = dk
        }
        
        val prevMsg = messages.getOrNull(i - 1)
        val nextMsg = messages.getOrNull(i + 1)
        
        // Grouping condition: Same sender and less than 5 minutes apart
        val MAX_GROUPING_DELAY = 5 * 60 * 1000L
        
        val isFirst = prevMsg == null || prevMsg.isMine != msg.isMine || prevMsg.senderName != msg.senderName
                      || (msg.timestampMs - prevMsg.timestampMs > MAX_GROUPING_DELAY) || (dayKey(prevMsg.timestampMs) != dk)
                      
        val isLast = nextMsg == null || nextMsg.isMine != msg.isMine || nextMsg.senderName != msg.senderName
                     || (nextMsg.timestampMs - msg.timestampMs > MAX_GROUPING_DELAY) || (dayKey(nextMsg.timestampMs) != dk)
                     
        result += ChatListItem.Msg(msg, isFirstInGroup = isFirst, isLastInGroup = isLast)
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupChatScreen(
    convId: String,
    displayName: String,
    viewModel: AdaCoreViewModel,
    onBack: () -> Unit,
    onStartCall: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    onOpenAdaLink: (String) -> Unit = {},
) {
    val groupIdHex = convId.removePrefix("g:")
    val messages by viewModel.messages.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val groupInfo by viewModel.currentGroupInfo.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val savedFiles by viewModel.savedFiles.collectAsState()
    val playingVoiceId by viewModel.playingVoiceId.collectAsState()
    val voiceProgress by viewModel.voiceProgress.collectAsState()
    val voiceDurationMs by viewModel.voiceDurationMs.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menuMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    val snackbarState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingCallType by remember { mutableStateOf<String?>(null) }
    var pendingJoinSessionId by remember { mutableStateOf<String?>(null) }
    var pendingJoinHasVideo by remember { mutableStateOf(false) }
    var manualPeerId by remember { mutableStateOf("") }
    var replyToMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMsg by remember { mutableStateOf<ChatMessage?>(null) }
    val chatPrefs = remember { context.getSharedPreferences("ada_chat_prefs", android.content.Context.MODE_PRIVATE) }
    var chatPattern by remember { mutableStateOf(ChatPattern.entries.getOrElse(chatPrefs.getInt("chat_pattern_$convId", 0)) { ChatPattern.SOLID }) }
    val recorderHelper = remember { com.ada.messenger.ui.components.MediaRecorderHelper(context) }
    DisposableEffect(recorderHelper) {
        onDispose { recorderHelper.cancel() }
    }

    val scope = rememberCoroutineScope()
    var showAttachSheet by remember { mutableStateOf(false) }

    // ── Attachment launchers ──────────────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val prepared = prepareCompressedImage(context, uri) ?: return@launch
                viewModel.sendAttachment(convId, prepared.fileName, prepared.mimeType, prepared.localPath)
            } catch (e: Exception) {
                android.util.Log.e("GroupChatScreen", "imagePicker read failed: ${e.message}")
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val rawName = resolveDisplayName(context, uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: "video_${System.currentTimeMillis()}"
                val mimeType = context.contentResolver.getType(uri)
                    ?.takeIf { it.startsWith("video/") }
                    ?: "video/mp4"
                val fileName = if (rawName.contains('.')) rawName else "$rawName.mp4"
                val localPath = stageAttachmentFromUri(context, uri, fileName) ?: return@launch
                viewModel.sendAttachment(convId, fileName, mimeType, localPath)
            } catch (e: Exception) {
                android.util.Log.e("GroupChatScreen", "videoPicker read failed: ${e.message}")
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val rawName = resolveDisplayName(context, uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")
                val fileName = if (rawName.contains('.')) rawName else "$rawName.bin"
                val mimeType = context.contentResolver.getType(uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val localPath = stageAttachmentFromUri(context, uri, fileName) ?: return@launch
                viewModel.sendAttachment(convId, fileName, mimeType, localPath)
            } catch (e: Exception) {
                android.util.Log.e("GroupChatScreen", "filePicker read failed: ${e.message}")
            }
        }
    }

    val memberCount = groupInfo?.memberCount ?: 0
    val canAddMembers = memberCount < 16
    val canStartVideo = memberCount <= 8
    val activeGroupCall = activeCall?.takeIf {
        it.groupIdHex == groupIdHex && !it.callSessionId.isNullOrBlank()
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingJoinSessionId != null && !pendingJoinHasVideo) {
            val sessionId = pendingJoinSessionId
            if (sessionId != null) {
                viewModel.joinGroupCall(groupIdHex, sessionId, false) { onStartCall() }
            }
        } else if (granted && pendingCallType == "audio") {
            viewModel.startGroupAudioCall(groupIdHex) { onStartCall() }
        }
        pendingCallType = null
        pendingJoinSessionId = null
        pendingJoinHasVideo = false
    }

    val videoPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ok = results.values.all { it }
        if (ok && pendingJoinSessionId != null) {
            val sessionId = pendingJoinSessionId
            if (sessionId != null) {
                viewModel.joinGroupCall(groupIdHex, sessionId, pendingJoinHasVideo) { onStartCall() }
            }
        } else if (ok && pendingCallType == "video") {
            viewModel.startGroupVideoCall(groupIdHex) { onStartCall() }
        }
        pendingCallType = null
        pendingJoinSessionId = null
        pendingJoinHasVideo = false
    }

    LaunchedEffect(sendError) {
        val err = sendError ?: return@LaunchedEffect
        snackbarState.showSnackbar(err)
        viewModel.clearSendError()
    }

    LaunchedEffect(convId) {
        viewModel.openConversation(convId)
        viewModel.loadGroupInfo(groupIdHex)
    }
    DisposableEffect(Unit) { onDispose { viewModel.closeConversation() } }

    val listItems = remember(messages) { buildChatListItems(messages) }

    val isNearBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 3
        }
    }

    LaunchedEffect(convId) {
        while (isActive) {
            kotlinx.coroutines.delay(2000L)
            viewModel.refreshChatMessages(convId)
        }
    }

    LaunchedEffect(listItems.size) {
        if (listItems.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(listItems.size - 1)
        }
    }

    // M-2: Stable callback refs for group chat
    val grpOnPlayVoice = remember<(String, String) -> Unit>(viewModel) {
        { tid, path -> viewModel.playVoice(tid, path) }
    }
    val grpOnStopVoice = remember<() -> Unit>(viewModel) { { viewModel.stopVoice() } }
    val grpOnLongPress = remember<(ChatMessage) -> Unit> { { msg -> menuMsg = msg } }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(displayName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        val count = groupInfo?.memberCount ?: 0
                        if (count > 0) {
                            Text(
                                "$count/16 " + stringResource(R.string.group_member_count),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startGroupAudioCall(groupIdHex) { onStartCall() }
                        } else {
                            pendingCallType = "audio"
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(Icons.Default.Call, contentDescription = stringResource(R.string.group_action_group_call))
                    }
                    IconButton(onClick = {
                        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (!canStartVideo) {
                            // Snackbar will show from sendError; use a direct approach
                            return@IconButton
                        }
                        if (micOk && camOk) {
                            viewModel.startGroupVideoCall(groupIdHex) { onStartCall() }
                        } else {
                            pendingCallType = "video"
                            val perms = buildList {
                                if (!micOk) add(Manifest.permission.RECORD_AUDIO)
                                if (!camOk) add(Manifest.permission.CAMERA)
                            }
                            videoPermLauncher.launch(perms.toTypedArray())
                        }
                    }, enabled = canStartVideo) {
                        Icon(Icons.Default.Videocam, contentDescription = "Видеозвонок")
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_menu_members)) },
                                onClick = { showOverflowMenu = false; showInfoDialog = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.group_action_add_member),
                                        color = if (canAddMembers) MaterialTheme.colorScheme.onSurface
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    if (canAddMembers) showInviteDialog = true
                                },
                                enabled = canAddMembers,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_action_clear_history)) },
                                onClick = { showOverflowMenu = false; showClearDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_action_leave), color = MaterialTheme.colorScheme.error) },
                                onClick = { showOverflowMenu = false; showLeaveDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_chat_background)) },
                                onClick = {
                                    showOverflowMenu = false
                                    val next = ChatPattern.entries[(chatPattern.ordinal + 1) % ChatPattern.entries.size]
                                    chatPattern = next
                                    chatPrefs.edit().putInt("chat_pattern_$convId", next.ordinal).apply()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().chatBackgroundPattern(chatPattern)) {
            AnimatedVisibility(visible = activeGroupCall != null) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (activeGroupCall?.hasVideo == true) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (activeGroupCall?.hasVideo == true) "Идёт групповой видеозвонок" else "Идёт групповой звонок",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            val participantCount = activeGroupCall?.participants?.size ?: 0
                            if (participantCount > 0) {
                                Text(
                                    text = "$participantCount участника в комнате",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                        TextButton(onClick = onStartCall) {
                            Text("Открыть")
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(listItems, key = { item ->
                    when (item) {
                        is ChatListItem.Separator -> "sep_${item.label}"
                        is ChatListItem.Msg -> item.msg.id
                    }
                }) { item ->
                    when (item) {
                        is ChatListItem.Separator -> {
                            val sepAlpha = remember { Animatable(0f) }
                            LaunchedEffect(Unit) { sepAlpha.animateTo(1f, tween(400)) }
                            Box(modifier = Modifier
                                .animateItemPlacement(tween(300))
                                .graphicsLayer { alpha = sepAlpha.value }
                            ) {
                                DateSeparator(item.label)
                            }
                        }
                        is ChatListItem.Msg -> {
                            val bubbleScale = remember { Animatable(0.92f) }
                            val bubbleAlpha = remember { Animatable(0f) }
                            LaunchedEffect(Unit) {
                                launch { bubbleScale.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
                                launch { bubbleAlpha.animateTo(1f, tween(200)) }
                            }
                            Box(modifier = Modifier
                                .animateItemPlacement(tween(300))
                                .graphicsLayer {
                                    scaleX = bubbleScale.value
                                    scaleY = bubbleScale.value
                                    alpha = bubbleAlpha.value
                                }
                            ) {
                            val transfer = transfers.find { it.id == item.msg.fileId || it.fileName == item.msg.text }
                            MessageBubble(
                                msg = item.msg,
                                transferProgress = transfer?.progress,
                                savedFiles = savedFiles,
                                playingVoiceId = playingVoiceId,
                                voiceProgress = voiceProgress,
                                voiceDurationMs = voiceDurationMs,
                                isFirstInGroup = item.isFirstInGroup,
                                isLastInGroup = item.isLastInGroup,
                                onPlayVoice = grpOnPlayVoice,
                                onStopVoice = grpOnStopVoice,
                                showSenderName = true,
                                onLongPress = { grpOnLongPress(item.msg) },
                                onToggleReaction = { emoji ->
                                    viewModel.toggleReaction(convId, item.msg.id, emoji, item.msg.myReactions)
                                },
                                onOpenImage = { _, _ -> },
                                onOpenUrl = onOpenUrl,
                                onOpenAdaLink = onOpenAdaLink,
                                onJoinGroupCall = { sessionId, hasVideo ->
                                    if (activeGroupCall?.callSessionId == sessionId) {
                                        onStartCall()
                                    } else {
                                        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                        if (micOk && (!hasVideo || camOk)) {
                                            viewModel.joinGroupCall(groupIdHex, sessionId, hasVideo) { onStartCall() }
                                        } else {
                                            pendingJoinSessionId = sessionId
                                            pendingJoinHasVideo = hasVideo
                                            if (hasVideo) {
                                                val perms = buildList {
                                                    if (!micOk) add(Manifest.permission.RECORD_AUDIO)
                                                    if (!camOk) add(Manifest.permission.CAMERA)
                                                }
                                                videoPermLauncher.launch(perms.toTypedArray())
                                            } else {
                                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                },
                                activeGroupCallSessionId = activeGroupCall?.callSessionId,
                                getDisplayPath = viewModel::getAttachmentForDisplay,
                            )
                            }
                        }
                    }
                }
            }
            // Validation and visual states for reply/edit
            AnimatedVisibility(visible = replyToMsg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (replyToMsg?.isMine == true) stringResource(R.string.chat_reply_you) else (replyToMsg?.senderName ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = replyToMsg?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { replyToMsg = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            AnimatedVisibility(visible = editingMsg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_editing_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = editingMsg?.text ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { editingMsg = null; text = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            HorizontalDivider()
            NewChatInputBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                text = text,
                onTextChanged = { text = it },
                onSendText = { ttl ->
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        text = ""
                        val editing = editingMsg
                        editingMsg = null
                        val reply = replyToMsg
                        replyToMsg = null
                        if (editing != null) {
                            viewModel.editMessage(convId, editing.id, t)
                        } else if (reply != null) {
                            viewModel.sendReply(convId, t, reply.id)
                        } else {
                            viewModel.sendText(convId, t, ttl)
                        }
                    }
                },
                onAttach = { showAttachSheet = true },
                onSendMedia = { file, mode ->
                    if (mode == MediaMode.VOICE) {
                        viewModel.sendAttachment(convId, file.name, "audio/ogg", file.absolutePath)
                    } else {
                        viewModel.sendAttachment(convId, file.name, "video/mp4", file.absolutePath)
                    }
                },
                recorderHelper = recorderHelper
            )
        }

        // Context menu
        if (menuMsg != null) {
            val m = menuMsg!!
            AlertDialog(
                onDismissRequest = { menuMsg = null },
                confirmButton = {},
                title = { Text(stringResource(R.string.dialog_message_title)) },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            val myReactions = m.myReactions
                            listOf("👍", "❤️", "😂", "😮", "😢", "🔥").forEach { emoji ->
                                val isActive = emoji in myReactions
                                val canAdd = isActive || myReactions.size < 3
                                TextButton(
                                    onClick = {
                                        viewModel.toggleReaction(convId, m.id, emoji, myReactions)
                                        menuMsg = null
                                    },
                                    enabled = canAdd,
                                ) {
                                    Text(
                                        emoji,
                                        fontSize = 24.sp,
                                        modifier = if (isActive) Modifier
                                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 4.dp)
                                        else Modifier,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                        TextButton(
                            onClick = {
                                replyToMsg = m
                                editingMsg = null
                                menuMsg = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.message_action_reply))
                        }
                        if (m.isMine && m.kind == "text") {
                            TextButton(
                                onClick = {
                                    text = m.text
                                    editingMsg = m
                                    replyToMsg = null
                                    menuMsg = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.message_action_edit)) }
                        }
                        TextButton(
                            onClick = { viewModel.deleteMessage(convId, m.id); menuMsg = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.message_action_delete_for_me)) }
                    }
                },
            )
        }

        // Clear chat
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(R.string.dialog_clear_confirm_title)) },
                text = { Text(stringResource(R.string.dialog_clear_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearConversation(convId); showClearDialog = false }) {
                        Text(stringResource(R.string.dialog_clear_confirm_button), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.button_cancel)) } },
            )
        }

        // Leave group confirmation
        if (showLeaveDialog) {
            AlertDialog(
                onDismissRequest = { showLeaveDialog = false },
                title = { Text(stringResource(R.string.dialog_leave_confirm_title)) },
                text = { Text(stringResource(R.string.dialog_leave_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.leaveGroup(groupIdHex)
                        showLeaveDialog = false
                        onBack()
                    }) { Text(stringResource(R.string.dialog_leave_confirm_button), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(R.string.button_cancel)) } },
            )
        }

        // Group info / members dialog
        if (showInfoDialog) {
            val info = groupInfo
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text(displayName) },
                text = {
                    if (info == null) {
                        Text(stringResource(R.string.status_loading))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (info.description.isNotBlank()) {
                                Text(info.description, style = MaterialTheme.typography.bodyMedium)
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Text(stringResource(R.string.group_info_members_header), style = MaterialTheme.typography.labelMedium)
                            info.members.forEach { member ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        member.displayName.ifBlank { member.peerIdB64.take(12) + "…" },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val roleLabel = when (member.role) {
                                        "Owner" -> stringResource(R.string.group_member_role_owner)
                                        "Admin" -> stringResource(R.string.group_member_role_admin)
                                        else -> ""
                                    }
                                    if (roleLabel.isNotBlank()) {
                                        Text(
                                            roleLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text(stringResource(R.string.button_close)) } },
            )
        }

        // Add member dialog
        if (showInviteDialog) {
            val currentMemberIds = groupInfo?.members?.map { it.peerIdB64 }?.toSet() ?: emptySet()
            val availableContacts = conversations.filter { conv ->
                conv.isDirect && conv.peerIdB64 != null && conv.peerIdB64 !in currentMemberIds
            }
            AddMemberDialog(
                availableContacts = availableContacts,
                currentCount = memberCount,
                onAdd = { peerId ->
                    viewModel.inviteToGroup(groupIdHex, peerId)
                    showInviteDialog = false
                },
                onDismiss = { showInviteDialog = false },
            )
        }
    }
}

