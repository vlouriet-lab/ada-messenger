package com.ada.messenger.desktop.core

import com.ada.messenger.desktop.model.DesktopAuthMode
import com.ada.messenger.desktop.model.DesktopCallAvailability
import com.ada.messenger.desktop.model.DesktopIncomingCall
import com.ada.messenger.desktop.model.DesktopBridgeStatus
import com.ada.messenger.desktop.model.DesktopChatMessage
import com.ada.messenger.desktop.model.DesktopConversationItem
import com.ada.messenger.desktop.model.DesktopSessionUiState
import com.ada.messenger.desktop.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.nio.file.Files
import java.nio.file.Paths

class DesktopSessionController(
    private val identityStore: DesktopIdentityStore = DesktopIdentityStore.default(),
    private val preferencesStore: DesktopPreferencesStore = DesktopPreferencesStore.default(),
) {
    private data class RuntimeSnapshot(
        val contactCardJson: String?,
        val contactShareLink: String?,
        val bridgeStatus: DesktopBridgeStatus?,
        val censorshipLevel: String,
        val metricsJson: String,
        val callAvailability: DesktopCallAvailability?,
        val activeCalls: List<com.ada.messenger.desktop.model.DesktopActiveCall>,
        val callHistory: List<com.ada.messenger.desktop.model.DesktopCallLogEntry>,
    )

    private data class PendingBridgeManifestImport(
        val manifestJson: String? = null,
        val manifestUrl: String? = null,
        val trustedPublicKeyHex: String?,
        val source: String,
        val noticeLabel: String,
        val persistBootstrap: Boolean = false,
    )

    private val preferences = preferencesStore.load()
    private val logFilePath = identityStore.coreDataDir.resolve("ada-desktop.log")
    private val initialPinEnabled = identityStore.isPinEnabled()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        DesktopSessionUiState(
            authMode = when {
                !identityStore.hasStoredIdentity() -> DesktopAuthMode.Register
                initialPinEnabled -> DesktopAuthMode.PinLogin
                else -> DesktopAuthMode.PatternLogin
            },
            pinEnabled = initialPinEnabled,
            themeMode = preferences.themeMode,
            storedDisplayName = identityStore.loadIdentityMeta()?.displayName,
            dataDirPath = identityStore.coreDataDir.toString(),
            logFilePath = logFilePath.toString(),
            customBootstrapUrl = preferences.customManifestUrl,
            customBootstrapPublicKey = preferences.customManifestPublicKey,
        ),
    )
    val state: StateFlow<DesktopSessionUiState> = _state.asStateFlow()
    private val _videoCallState = MutableStateFlow(DesktopVideoCallUiState())
    val videoCallState: StateFlow<DesktopVideoCallUiState> = _videoCallState.asStateFlow()

    @Volatile
    private var core: DesktopAdaCore? = null
    private var webRtcBridge: DesktopWebRtcBridge? = null
    private var unlockedPatternCells: ByteArray? = null
    private var pollJob: Job? = null

    // Tracks blob_ref messages that have an available hash for downloading (from BlobAvailable events).
    private data class BlobDownloadInfo(val fromPeerB64: String, val hashHex: String)
    private val blobDownloadMap = mutableMapOf<String, BlobDownloadInfo>() // fileId -> info
    private val completedTransferFileIds = mutableSetOf<String>() // fileId of completed inbound transfers

    init {
        DesktopCallLog.configure(identityStore.coreDataDir)
        DesktopCallLog.info("desktop session controller initialized")
    }

    fun createFromPattern(cells: ByteArray, displayName: String, pin: String? = null) {
        if (_state.value.patternLoading) return
        val trimmedName = displayName.trim()
        val normalizedPin = pin?.trim().orEmpty()
        if (cells.size != 32) {
            setPatternError("Нужно выбрать ровно 16 кубиков")
            return
        }
        if (trimmedName.isBlank()) {
            setPatternError("Введите отображаемое имя")
            return
        }
        if (normalizedPin.isNotEmpty() && (normalizedPin.length != 4 || !normalizedPin.all(Char::isDigit))) {
            setPatternError("PIN должен состоять ровно из 4 цифр")
            return
        }

        scope.launch {
            _state.update {
                it.copy(
                    authMode = DesktopAuthMode.Register,
                    patternLoading = true,
                    patternError = null,
                    sendError = null,
                )
            }

            try {
                DesktopAdaCore.initTracing(identityStore.coreDataDir.toString())
                val instance = DesktopAdaCore.createFromPattern(
                    cells = cells,
                    displayName = trimmedName,
                    dataDir = identityStore.coreDataDir.toString(),
                    connectionProfile = DEFAULT_DESKTOP_CONNECTION_PROFILE,
                ) ?: run {
                    setPatternError("Не удалось создать профиль. Rust core вернул пустой handle.")
                    return@launch
                }

                val peerId = instance.getPeerId().orEmpty()
                if (peerId.isBlank()) {
                    instance.close()
                    setPatternError("Профиль создан, но peer id не был получен из core.")
                    return@launch
                }

                identityStore.saveIdentityMeta(peerId, trimmedName, DEFAULT_DESKTOP_CONNECTION_PROFILE)
                DesktopCallLog.info(
                    "desktop profile created; peer=${shortPeerId(peerId)} profile=$DEFAULT_DESKTOP_CONNECTION_PROFILE",
                )
                if (normalizedPin.isNotEmpty()) {
                    identityStore.enablePin(normalizedPin, cells)
                }
                bindCore(instance, trimmedName, peerId, cells)
            } catch (error: Throwable) {
                DesktopCallLog.warn("desktop profile creation failed", error)
                setPatternError(error.message ?: "Не удалось открыть native ada_core runtime.")
            }
        }
    }

    fun loginWithPattern(cells: ByteArray) {
        if (_state.value.patternLoading) return
        if (cells.size != 32) {
            setPatternError("Нужно выбрать ровно 16 кубиков")
            return
        }

        val stored = identityStore.loadIdentityMeta()
        if (stored == null) {
            _state.update {
                it.copy(
                    authMode = DesktopAuthMode.Register,
                    pinEnabled = false,
                    storedDisplayName = null,
                    patternError = "Локальный профиль не найден. Создайте новый.",
                )
            }
            return
        }

        scope.launch {
            _state.update {
                it.copy(
                    authMode = DesktopAuthMode.PatternLogin,
                    storedDisplayName = stored.displayName,
                    patternLoading = true,
                    patternError = null,
                    sendError = null,
                )
            }

            try {
                val effectiveProfile = preferredDesktopConnectionProfile(stored.connectionProfile)
                DesktopAdaCore.initTracing(identityStore.coreDataDir.toString())
                val instance = DesktopAdaCore.createFromPattern(
                    cells = cells,
                    displayName = stored.displayName,
                    dataDir = identityStore.coreDataDir.toString(),
                    connectionProfile = effectiveProfile,
                ) ?: run {
                    setPatternError("Не удалось открыть локальный профиль.")
                    return@launch
                }

                val derivedPeerId = instance.getPeerId().orEmpty()
                if (derivedPeerId != stored.peerId) {
                    instance.close()
                    setPatternError("Неверный узор. Повторите ещё раз.")
                    return@launch
                }

                if (effectiveProfile != stored.connectionProfile) {
                    identityStore.updateConnectionProfile(effectiveProfile)
                    DesktopCallLog.info(
                        "desktop connection profile migrated on pattern login: ${stored.connectionProfile} -> $effectiveProfile",
                    )
                }

                bindCore(instance, stored.displayName, derivedPeerId, cells)
            } catch (error: Throwable) {
                DesktopCallLog.warn("desktop pattern login failed", error)
                setPatternError(error.message ?: "Не удалось открыть native ada_core runtime.")
            }
        }
    }

    // ── Wi-Fi link (phone → desktop snapshot sync) ────────────────────────────

    private var wifiLinkServer: WifiLinkServer? = null
    @Volatile private var pendingSnapshotJson: String? = null
    private var syncReceiveServer: SyncReceiveServer? = null

    /**
     * Start the local HTTP server, update state with the QR URL, and transition
     * to [DesktopAuthMode.WifiLinkWaiting].
     *
     * When the phone POSTs the snapshot the server calls back on a background
     * thread.  We transition to [DesktopAuthMode.SnapshotPatternSetup] so the
     * UI can prompt the user to set a pattern that will protect the desktop DB.
     */
    fun startWifiLinkServer() {
        stopWifiLinkServer()

        // Start the persistent sync server first so we can embed its port in the QR response.
        val syncServer = SyncReceiveServer(
            linkKeyProvider = { core?.getLinkKeyHex() },
            onSyncPush = { linkKeyHex, dataB64 ->
                val c = core
                if (c != null) {
                    val ok = c.handleSyncPush(linkKeyHex, dataB64)
                    if (!ok) DesktopCallLog.warn("SyncReceiveServer: handleSyncPush returned false")
                } else {
                    DesktopCallLog.warn("SyncReceiveServer: push received but core is null")
                }
            },
            onError = { msg -> DesktopCallLog.warn("SyncReceiveServer: $msg") },
        )
        try {
            syncServer.start()
            syncReceiveServer = syncServer
        } catch (e: Exception) {
            DesktopCallLog.warn("SyncReceiveServer start failed", e)
        }

        val server = WifiLinkServer(
            onSnapshotReceived = { snapshotJson ->
                pendingSnapshotJson = snapshotJson
                wifiLinkServer = null
                _state.update {
                    it.copy(
                        wifiLinkUrl = null,
                        wifiLinkActive = false,
                        authMode = DesktopAuthMode.SnapshotPatternSetup,
                        patternError = null,
                    )
                }
            },
            onError = { msg ->
                DesktopCallLog.warn("WifiLinkServer: $msg")
            },
            syncPortProvider = { syncReceiveServer?.port ?: 0 },
        )
        try {
            server.start()
            wifiLinkServer = server
            _state.update {
                it.copy(
                    authMode = DesktopAuthMode.WifiLinkWaiting,
                    wifiLinkUrl = server.linkUrl,
                    wifiLinkActive = true,
                    patternError = null,
                )
            }
        } catch (e: Exception) {
            DesktopCallLog.warn("startWifiLinkServer failed", e)
            setPatternError("Не удалось запустить сервер синхронизации: ${e.message}")
        }
    }

    /** Cancel the Wi-Fi link server and return to Register screen. */
    fun stopWifiLinkServer() {
        wifiLinkServer?.stop()
        wifiLinkServer = null
        pendingSnapshotJson = null
        // Stop the sync receive server too — it will be re-started on next pairing attempt
        // or when the core boots from a previously paired identity.
        syncReceiveServer?.stop()
        syncReceiveServer = null
        if (_state.value.authMode == DesktopAuthMode.WifiLinkWaiting ||
            _state.value.authMode == DesktopAuthMode.SnapshotPatternSetup
        ) {
            _state.update {
                it.copy(
                    authMode = DesktopAuthMode.Register,
                    wifiLinkUrl = null,
                    wifiLinkActive = false,
                    patternError = null,
                )
            }
        }
    }

    /**
     * Called after the snapshot is received and the user has drawn a pattern.
     *
     * Creates the desktop core from the snapshot, saves identity metadata,
     * and transitions to the main app screen.
     */
    fun createFromSnapshotWithPattern(cells: ByteArray) {
        if (_state.value.patternLoading) return
        if (cells.size != 32) {
            setPatternError("Нужно выбрать ровно 16 кубиков")
            return
        }
        val snapshot = pendingSnapshotJson
        if (snapshot == null) {
            setPatternError("Снапшот аккаунта не найден. Отсканируйте QR заново.")
            return
        }

        scope.launch {
            _state.update { it.copy(patternLoading = true, patternError = null) }
            try {
                // Extract display name from snapshot JSON for the identity store.
                val displayName = try {
                    JSONObject(snapshot)
                        .getJSONObject("identity")
                        .optString("display_name", "Пользователь")
                } catch (_: Exception) {
                    "Пользователь"
                }

                DesktopAdaCore.initTracing(identityStore.coreDataDir.toString())
                val instance = DesktopAdaCore.createFromSnapshot(
                    cells = cells,
                    snapshotJson = snapshot,
                    dataDir = identityStore.coreDataDir.toString(),
                    connectionProfile = DEFAULT_DESKTOP_CONNECTION_PROFILE,
                ) ?: run {
                    setPatternError("Не удалось создать профиль из снапшота.")
                    return@launch
                }

                val peerId = instance.getPeerId().orEmpty()
                if (peerId.isBlank()) {
                    instance.close()
                    setPatternError("Снапшот не содержит действительного идентификатора.")
                    return@launch
                }

                identityStore.saveIdentityMeta(peerId, displayName, DEFAULT_DESKTOP_CONNECTION_PROFILE)
                pendingSnapshotJson = null
                DesktopCallLog.info("snapshot import complete; peer=${shortPeerId(peerId)}")
                bindCore(instance, displayName, peerId, cells)
            } catch (error: Throwable) {
                DesktopCallLog.warn("createFromSnapshotWithPattern failed", error)
                setPatternError(error.message ?: "Ошибка импорта снапшота.")
            }
        }
    }

    fun loginWithPin(pin: String) {
        if (_state.value.patternLoading) return
        val normalizedPin = pin.trim()
        if (normalizedPin.length != 4 || !normalizedPin.all(Char::isDigit)) {
            setPatternError("PIN должен состоять ровно из 4 цифр")
            return
        }

        val stored = identityStore.loadIdentityMeta()
        if (stored == null) {
            _state.update {
                it.copy(
                    authMode = DesktopAuthMode.Register,
                    pinEnabled = false,
                    storedDisplayName = null,
                    patternError = "Локальный профиль не найден. Создайте новый.",
                )
            }
            return
        }

        val cells = identityStore.decryptCellsWithPin(normalizedPin)
        if (cells == null || cells.size != 32) {
            setPatternError("Неверный PIN. Попробуйте ещё раз или войдите рисунком.")
            return
        }

        loginWithStoredCells(
            stored = stored,
            cells = cells,
            authMode = DesktopAuthMode.PinLogin,
            invalidCredentialMessage = "Неверный PIN. Попробуйте ещё раз или войдите рисунком.",
        )
    }

    fun switchToPatternLogin() {
        val stored = identityStore.loadIdentityMeta()
        _state.update {
            it.copy(
                authMode = if (stored == null) DesktopAuthMode.Register else DesktopAuthMode.PatternLogin,
                storedDisplayName = stored?.displayName,
                patternError = null,
            )
        }
    }

    fun switchToPinLogin() {
        if (!identityStore.hasStoredIdentity() || !identityStore.isPinEnabled()) return
        _state.update {
            it.copy(
                authMode = DesktopAuthMode.PinLogin,
                pinEnabled = true,
                patternError = null,
            )
        }
    }

    suspend fun enablePinFromSession(newPin: String): String? = withContext(Dispatchers.Default) {
        if (identityStore.isPinEnabled()) {
            return@withContext "PIN уже включён. Используйте смену PIN."
        }

        val normalizedPin = normalizePin(newPin)
            ?: return@withContext "PIN должен состоять ровно из 4 цифр"
        val cells = copyUnlockedPatternCells()
            ?: return@withContext "Нет данных рисунка. Войдите рисунком и повторите."

        try {
            identityStore.enablePin(normalizedPin, cells)
            _state.update { it.copy(pinEnabled = true) }
            setActionMessage("PIN установлен. Следующий вход начнётся с PIN-экрана.")
            null
        } catch (error: Throwable) {
            error.message ?: "Не удалось сохранить PIN."
        } finally {
            cells.fill(0)
        }
    }

    suspend fun changePinFromSession(currentPin: String, newPin: String): String? = withContext(Dispatchers.Default) {
        if (!identityStore.isPinEnabled()) {
            return@withContext "PIN ещё не включён."
        }

        val normalizedCurrentPin = normalizePin(currentPin)
            ?: return@withContext "Введите текущий PIN из 4 цифр"
        val normalizedNewPin = normalizePin(newPin)
            ?: return@withContext "Новый PIN должен состоять ровно из 4 цифр"
        val cells = identityStore.decryptCellsWithPin(normalizedCurrentPin)
            ?: return@withContext "Неверный текущий PIN"

        try {
            identityStore.enablePin(normalizedNewPin, cells)
            _state.update { it.copy(pinEnabled = true) }
            setActionMessage("PIN изменён.")
            null
        } catch (error: Throwable) {
            error.message ?: "Не удалось изменить PIN."
        } finally {
            cells.fill(0)
        }
    }

    suspend fun disablePinFromSession(currentPin: String): String? = withContext(Dispatchers.Default) {
        if (!identityStore.isPinEnabled()) {
            return@withContext "PIN уже отключён."
        }

        val normalizedCurrentPin = normalizePin(currentPin)
            ?: return@withContext "Введите текущий PIN из 4 цифр"
        val cells = identityStore.decryptCellsWithPin(normalizedCurrentPin)
            ?: return@withContext "Неверный текущий PIN"

        try {
            identityStore.disablePin()
            _state.update { it.copy(pinEnabled = false) }
            setActionMessage("PIN отключён. Следующий вход будет через рисунок.")
            null
        } catch (error: Throwable) {
            error.message ?: "Не удалось отключить PIN."
        } finally {
            cells.fill(0)
        }
    }

    fun importContactFromText(input: String): Boolean {
        val contactJson = parseContactImportInput(input) ?: run {
            setActionMessage("Невалидная contact card или ada:// ссылка.", isError = true)
            return false
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val contact = parseContactIdentity(contactJson)
            val ok = activeCore.addContactFromJson(contactJson)
            if (ok) {
                val ensuredConversation = contact?.id?.let { peerId ->
                    DesktopConversationItem(
                        id = "d:$peerId",
                        displayName = contact.displayName ?: shortPeerId(peerId),
                        lastMessage = "",
                        lastActivityMs = System.currentTimeMillis(),
                        unreadCount = 0,
                    )
                }
                refreshFromCore(
                    preferredConversationId = contact?.id?.let { "d:$it" },
                    ensuredConversation = ensuredConversation,
                )
                setActionMessage("Контакт добавлен. Direct chat готов.")
            } else {
                setActionMessage(
                    "Не удалось импортировать контакт. Проверьте payload и повторите.",
                    isError = true,
                )
            }
        }

        return true
    }

    fun importContactFromFile(path: String) {
        val text = readTextFile(path)
        if (text == null) {
            setActionMessage("Не удалось прочитать contact payload из файла.", isError = true)
            return
        }
        importContactFromText(text)
    }

    fun importContactFromQrImage(path: String) {
        val text = decodeQrTextFromImage(path)
        if (text == null) {
            setActionMessage("Не удалось распознать QR-код из изображения.", isError = true)
            return
        }
        importContactFromText(text)
    }

    fun importBridgeManifestFromText(
        input: String,
        trustedPublicKeyHex: String? = null,
        sourceHint: String = "manual",
    ): Boolean {
        val payload = parseBridgeManifestImportInput(input, trustedPublicKeyHex, sourceHint)
        if (payload == null) {
            setActionMessage("Невалидный bridge manifest payload или URL.", isError = true)
            return false
        }
        importBridgeManifestPayload(payload)
        return true
    }

    fun importBridgeManifestFromFile(path: String, trustedPublicKeyHex: String? = null) {
        val text = readTextFile(path)
        if (text == null) {
            setActionMessage("Не удалось прочитать bridge manifest из файла.", isError = true)
            return
        }

        val sourceLabel = runCatching { Paths.get(path).fileName?.toString().orEmpty() }.getOrDefault(path)
        importBridgeManifestFromText(
            input = text,
            trustedPublicKeyHex = trustedPublicKeyHex,
            sourceHint = "file:${sourceLabel.ifBlank { "manifest" }}",
        )
    }

    fun importBridgeManifestFromQrImage(path: String, trustedPublicKeyHex: String? = null) {
        val text = decodeQrTextFromImage(path)
        if (text == null) {
            setActionMessage("Не удалось распознать QR bridge manifest из изображения.", isError = true)
            return
        }

        importBridgeManifestFromText(
            input = text,
            trustedPublicKeyHex = trustedPublicKeyHex,
            sourceHint = "qr-image",
        )
    }

    fun importCustomBridgeBootstrap(manifestUrl: String, trustedPublicKeyHex: String): Boolean {
        val normalizedUrl = manifestUrl.trim().removePrefix("<").removeSuffix(">")
        if (!normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("http://")) {
            setActionMessage("Custom bootstrap должен быть http(s) URL.", isError = true)
            return false
        }

        importBridgeManifestPayload(
            PendingBridgeManifestImport(
                manifestUrl = normalizedUrl,
                trustedPublicKeyHex = normalizeManifestPublicKeyHex(trustedPublicKeyHex),
                source = normalizedUrl,
                noticeLabel = sourceLabelForManifestUrl(normalizedUrl),
                persistBootstrap = true,
            ),
        )
        return true
    }

    fun addBridge(bridgeLine: String) {
        val normalized = bridgeLine.trim()
        if (normalized.isBlank()) {
            setActionMessage("Введите bridge line.", isError = true)
            return
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val result = activeCore.addBridgeDetailed(normalized)
            if (result.success) {
                refreshFromCore()
                setActionMessage("Bridge добавлен.")
            } else {
                setActionMessage(formatBridgeFailure(result.error), isError = true)
            }
        }
    }

    fun setBridgeMode(modeStr: String) {
        val normalized = modeStr.trim().lowercase()
        if (normalized.isBlank()) {
            setActionMessage("Укажите bridge mode.", isError = true)
            return
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            if (activeCore.setBridgeMode(normalized)) {
                refreshFromCore()
                setActionMessage("Bridge mode обновлён: $normalized")
            } else {
                setActionMessage("Не удалось обновить bridge mode.", isError = true)
            }
        }
    }

    fun detectCensorship() {
        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val level = parseCensorshipLevel(activeCore.detectCensorshipJson())
            _state.update { it.copy(censorshipLevel = level) }
            setActionMessage("Проверка сети завершена: $level")
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        preferencesStore.saveThemeMode(themeMode)
        _state.update { it.copy(themeMode = themeMode) }
    }

    fun renameContact(peerId: String, displayName: String) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) {
            setActionMessage("Не удалось определить контакт для переименования.", isError = true)
            return
        }

        preferencesStore.saveContactAlias(normalizedPeerId, displayName)
        scope.launch {
            refreshFromCore(preferredConversationId = _state.value.activeConversationId)
            setActionMessage(
                if (displayName.trim().isBlank()) {
                    "Локальное имя контакта сброшено."
                } else {
                    "Имя контакта обновлено."
                },
            )
        }
    }

    fun requestAudioCall() {
        if (activeDirectPeerId() == null) {
            setActionMessage("Звонки доступны только в direct chat.", isError = true)
            return
        }
        if (_state.value.incomingCall != null) {
            setActionMessage("Сначала обработайте входящий звонок в разделе звонков.", isError = true)
            return
        }
        if (_state.value.activeCalls.isNotEmpty()) {
            setActionMessage("Сначала завершите текущий звонок в разделе звонков.", isError = true)
            return
        }
        DesktopCallLog.info("desktop outgoing audio call requested for peer=${shortPeerId(activeDirectPeerId().orEmpty())}")
        startDesktopCall("Голосовой звонок", hasVideo = false)
    }

    fun requestVideoCall() {
        if (activeDirectPeerId() == null) {
            setActionMessage("Видеозвонки доступны только в direct chat.", isError = true)
            return
        }
        if (_state.value.incomingCall != null) {
            setActionMessage("Сначала обработайте входящий звонок в разделе звонков.", isError = true)
            return
        }
        if (_state.value.activeCalls.isNotEmpty()) {
            setActionMessage("Сначала завершите текущий звонок в разделе звонков.", isError = true)
            return
        }
        DesktopCallLog.info("desktop outgoing video call requested for peer=${shortPeerId(activeDirectPeerId().orEmpty())}")
        startDesktopCall("Видеозвонок", hasVideo = true)
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        if (!mediaBridge().setLocalAudioEnabled(enabled)) {
            setActionMessage("Локальный микрофон для этого звонка недоступен.", isError = true)
        }
    }

    fun setLocalVideoEnabled(enabled: Boolean) {
        if (!mediaBridge().setLocalVideoEnabled(enabled)) {
            setActionMessage("Локальная камера для этого звонка недоступна.", isError = true)
        }
    }

    fun cycleAudioOutputDevice() {
        if (!mediaBridge().cycleAudioOutputDevice()) {
            setActionMessage("На desktop нет альтернативного audio output для текущего звонка.", isError = true)
        }
    }

    fun cycleVideoDevice() {
        if (!mediaBridge().cycleVideoDevice()) {
            setActionMessage("Переключение камеры на desktop сейчас недоступно.", isError = true)
        }
    }

    fun toggleScreenShare() {
        if (!mediaBridge().toggleScreenShare()) {
            setActionMessage("Screen share на desktop сейчас недоступен для этого звонка.", isError = true)
        }
    }

    fun answerIncomingCall() {
        val incoming = _state.value.incomingCall ?: return
        if (incoming.groupId != null || incoming.callSessionId != null) {
            setActionMessage("Групповые звонки на desktop будут подключены отдельным media patch.", isError = true)
            return
        }

        scope.launch {
            DesktopCallLog.info(
                "desktop answering incoming call id=${incoming.callId} peer=${shortPeerId(incoming.peerId)} video=${incoming.hasVideo}",
            )
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val answerResult = mediaBridge().answerIncomingCall(
                callIdHex = incoming.callId,
                peerIdB64 = incoming.peerId,
                offerSdp = incoming.offerSdp,
                hasVideo = incoming.hasVideo,
            )
            if (answerResult.success) {
                _state.update { it.copy(incomingCall = null) }
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                DesktopCallLog.info(
                    "desktop incoming call answered id=${incoming.callId} peer=${shortPeerId(incoming.peerId)} video=${incoming.hasVideo}",
                )
                setActionMessage(
                    if (incoming.hasVideo) {
                        "Видеозвонок с ${incoming.displayName} принят. Desktop media engine активен."
                    } else {
                        "Звонок с ${incoming.displayName} принят. Desktop media engine активен."
                    },
                )
            } else {
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                DesktopCallLog.warn(
                    "desktop incoming call answer failed id=${incoming.callId} peer=${shortPeerId(incoming.peerId)} reason=${answerResult.error}",
                )
                setActionMessage(answerResult.error ?: "Не удалось принять входящий звонок.", isError = true)
            }
        }
    }

    fun declineIncomingCall() {
        val incoming = _state.value.incomingCall ?: return

        scope.launch {
            DesktopCallLog.info(
                "desktop decline requested id=${incoming.callId} peer=${shortPeerId(incoming.peerId)}",
            )
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val declineResult = activeCore.declineCallDetailed(incoming.callId, incoming.peerId)
            if (declineResult.success) {
                webRtcBridge?.onCallEnded(callIdHex = incoming.callId, peerIdB64 = incoming.peerId)
                _state.update { it.copy(incomingCall = null) }
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                setActionMessage("Входящий звонок отклонён.")
            } else {
                setActionMessage(declineResult.error ?: "Не удалось отклонить входящий звонок.", isError = true)
            }
        }
    }

    fun hangupActiveCall(callId: String, peerId: String) {
        val normalizedCallId = callId.trim()
        val normalizedPeerId = peerId.trim()
        if (normalizedCallId.isBlank() || normalizedPeerId.isBlank()) {
            setActionMessage("Сброс этого звонка на desktop пока не поддержан.", isError = true)
            return
        }

        scope.launch {
            DesktopCallLog.info("desktop hangup requested id=$normalizedCallId peer=${shortPeerId(normalizedPeerId)}")
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val hangupResult = activeCore.hangupDetailed(normalizedCallId, normalizedPeerId)
            if (hangupResult.success) {
                webRtcBridge?.onCallEnded(callIdHex = normalizedCallId, peerIdB64 = normalizedPeerId)
                _state.update {
                    it.copy(
                        incomingCall = it.incomingCall?.takeUnless { incoming -> incoming.callId == normalizedCallId },
                    )
                }
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                setActionMessage("Звонок сброшен.")
            } else {
                setActionMessage(hangupResult.error ?: "Не удалось сбросить звонок.", isError = true)
            }
        }
    }

    fun hangupGroupCall(sessionId: String) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) {
            setActionMessage("Невозможно сбросить групповой звонок: нет session ID.", isError = true)
            return
        }

        scope.launch {
            DesktopCallLog.info("desktop hangup group call room session=$normalizedSessionId")
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val ok = activeCore.hangupGroupCallRoom(normalizedSessionId)
            if (ok) {
                _state.update {
                    it.copy(
                        incomingCall = it.incomingCall?.takeUnless { incoming -> incoming.callSessionId == normalizedSessionId },
                    )
                }
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                setActionMessage("Групповой звонок сброшен.")
            } else {
                setActionMessage("Не удалось сбросить групповой звонок.", isError = true)
            }
        }
    }

    fun sendAttachmentFromPath(path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) {
            setActionMessage("Файл не выбран.", isError = true)
            return
        }

        val conversationId = _state.value.activeConversationId
        val peerId = activeDirectPeerId()
        if (conversationId == null || peerId == null) {
            setActionMessage("Вложения пока поддерживаются только в direct chat.", isError = true)
            return
        }

        val filePath = runCatching { Paths.get(normalizedPath) }.getOrNull()
        if (filePath == null || !Files.isRegularFile(filePath)) {
            setActionMessage("Не удалось открыть выбранный файл.", isError = true)
            return
        }

        val fileName = filePath.fileName?.toString().orEmpty().trim()
        if (fileName.isBlank()) {
            setActionMessage("У файла нет корректного имени.", isError = true)
            return
        }

        val mimeType = detectMimeType(filePath)
        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val transferId = activeCore.sendFileFromPath(peerId, fileName, mimeType, normalizedPath)
            if (transferId != null) {
                refreshFromCore(preferredConversationId = conversationId)
                setActionMessage("Файл поставлен в отправку: $fileName")
            } else {
                setActionMessage("Не удалось прикрепить файл.", isError = true)
            }
        }
    }

    /** Called after the user picks a save path via dialog. Copies the received file to [destPath]. */
    fun saveFileToPath(message: DesktopChatMessage, destPath: String) {
        if (message.fileId.isBlank()) {
            setActionMessage("Нет данных о файле.", isError = true)
            return
        }
        scope.launch {
            val activeCore = core ?: run {
                setActionMessage("Runtime недоступен.", isError = true)
                return@launch
            }
            // Ensure destination directory exists
            runCatching {
                val parent = Paths.get(destPath).parent
                if (parent != null) Files.createDirectories(parent)
            }
            val ok = when (message.kind) {
                "file" -> activeCore.saveTransferToFile(message.fileId, destPath) != null
                "blob_ref" -> {
                    val info = blobDownloadMap[message.fileId]
                    if (info != null) {
                        activeCore.fetchBlobToFile(info.fromPeerB64, info.hashHex, destPath)
                    } else {
                        setActionMessage("Данные для скачивания устарели. Попросите отправителя повторить.", isError = true)
                        return@launch
                    }
                }
                else -> false
            }
            if (ok) {
                // Remove from downloadable set — for file kind the cache entry is consumed
                if (message.kind == "file") {
                    completedTransferFileIds.remove(message.fileId)
                    _state.update { current ->
                        current.copy(downloadableFileIds = current.downloadableFileIds - message.fileId)
                    }
                }
                setActionMessage("Файл сохранён.")
            } else {
                setActionMessage("Не удалось сохранить файл.", isError = true)
            }
        }
    }

    fun deleteMessage(messageId: String) {
        val normalizedMessageId = messageId.trim()
        if (normalizedMessageId.isBlank()) return

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            if (activeCore.deleteMessage(normalizedMessageId)) {
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                setActionMessage("Сообщение удалено.")
            } else {
                setActionMessage("Не удалось удалить сообщение.", isError = true)
            }
        }
    }

    fun deleteMessageForEveryone(messageId: String) {
        val normalizedMessageId = messageId.trim()
        val peerId = activeDirectPeerId()
        if (normalizedMessageId.isBlank() || peerId == null) {
            setActionMessage("Удаление у всех доступно только в direct chat.", isError = true)
            return
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            if (activeCore.deleteMessageForEveryone(peerId, normalizedMessageId)) {
                refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                setActionMessage("Сообщение удалено у всех.")
            } else {
                setActionMessage("Не удалось удалить сообщение у всех.", isError = true)
            }
        }
    }

    fun openDataDirectory() {
        if (!openSystemPath(_state.value.dataDirPath)) {
            setActionMessage("Не удалось открыть data directory.", isError = true)
        }
    }

    fun openLogFile() {
        if (!openSystemPath(_state.value.logFilePath)) {
            setActionMessage("Не удалось открыть desktop log файл.", isError = true)
        }
    }

    fun requestIdentityExportPreview() {
        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val exportJson = activeCore.exportIdentityJson()
            if (exportJson.isNullOrBlank()) {
                setActionMessage("Не удалось сформировать identity export JSON.", isError = true)
                return@launch
            }

            _state.update { it.copy(identityExportJson = exportJson) }
            setActionMessage("Identity export готов. Храните его как секретный backup.")
        }
    }

    fun clearIdentityExportPreview() {
        _state.update { it.copy(identityExportJson = null) }
    }

    fun exportIdentityBackupToFile(path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) {
            setActionMessage("Укажите путь для backup файла.", isError = true)
            return
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val exportJson = activeCore.exportIdentityJson()
            if (exportJson.isNullOrBlank()) {
                setActionMessage("Не удалось сформировать identity export JSON.", isError = true)
                return@launch
            }

            if (writeTextFile(normalizedPath, exportJson)) {
                _state.update { it.copy(identityExportJson = exportJson) }
                setActionMessage("Identity backup сохранён: $normalizedPath")
            } else {
                setActionMessage("Не удалось сохранить identity backup файл.", isError = true)
            }
        }
    }

    fun setConnectionProfile(profile: String) {
        val normalized = normalizeConnectionProfile(profile)
        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            if (activeCore.setConnectionProfile(normalized)) {
                identityStore.updateConnectionProfile(normalized)
                refreshFromCore()
                setActionMessage("Профиль сети обновлён: ${formatConnectionProfile(normalized)}")
            } else {
                setActionMessage("Не удалось сменить connection profile.", isError = true)
            }
        }
    }

    fun setRelayOnly(enabled: Boolean) {
        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            if (activeCore.setRelayOnly(enabled)) {
                refreshFromCore()
                setActionMessage(
                    if (enabled) {
                        "Relay-only routing включён. Direct iroh временно отключён."
                    } else {
                        "Relay-only routing отключён. Автовыбор маршрута восстановлен."
                    },
                )
            } else {
                setActionMessage("Не удалось изменить relay-only режим.", isError = true)
            }
        }
    }

    fun addRelayNode(relayUrl: String) {
        val normalized = relayUrl.trim()
        if (normalized.isBlank()) {
            setActionMessage("Введите relay URL.", isError = true)
            return
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            if (activeCore.addRelayNode(normalized)) {
                refreshFromCore()
                setActionMessage("Relay добавлен: $normalized")
            } else {
                setActionMessage("Не удалось добавить relay URL.", isError = true)
            }
        }
    }

    fun openConversation(conversationId: String) {
        scope.launch {
            core?.markRead(conversationId)
            _state.update { it.copy(activeConversationId = conversationId, sendError = null, actionMessage = null) }
            refreshFromCore(preferredConversationId = conversationId)
        }
    }

    fun updateDraft(value: String) {
        _state.update { it.copy(draft = value, sendError = null) }
    }

    fun sendDraft() {
        val snapshot = _state.value
        val conversationId = snapshot.activeConversationId ?: return
        val text = snapshot.draft.trim()
        if (text.isEmpty()) return

        val optimistic = DesktopChatMessage(
            id = "optimistic-${System.nanoTime()}",
            sender = snapshot.peerId.orEmpty(),
            senderName = snapshot.displayName.orEmpty(),
            text = text,
            timestampMs = System.currentTimeMillis(),
            isMine = true,
            status = "sending",
            kind = "text",
        )

        _state.update {
            it.copy(
                draft = "",
                sendError = null,
                messages = it.messages + optimistic,
            )
        }

        scope.launch {
            val activeCore = core
            val ok = when {
                activeCore == null -> false
                conversationId.startsWith("d:") -> activeCore.sendText(conversationId.removePrefix("d:"), text)
                conversationId.startsWith("g:") -> activeCore.sendGroupText(conversationId.removePrefix("g:"), text)
                else -> false
            }

            if (ok) {
                refreshFromCore(preferredConversationId = conversationId)
            } else {
                _state.update { current ->
                    current.copy(
                        messages = current.messages.filterNot { it.id == optimistic.id },
                        sendError = "Не удалось отправить сообщение. Проверьте, что контакт уже добавлен и native library доступна desktop приложению.",
                    )
                }
            }
        }
    }

    fun clearPatternError() {
        _state.update { it.copy(patternError = null) }
    }

    fun clearSendError() {
        _state.update { it.copy(sendError = null) }
    }

    fun clearActionMessage() {
        _state.update { it.copy(actionMessage = null, actionMessageIsError = false) }
    }

    fun refreshWorkspace() {
        scope.launch {
            refreshFromCore()
        }
    }

    fun close() {
        pollJob?.cancel()
        pollJob = null
        clearUnlockedPatternCells()
        webRtcBridge?.dispose()
        webRtcBridge = null
        _videoCallState.value = DesktopVideoCallUiState()
        syncReceiveServer?.stop()
        syncReceiveServer = null
        core?.close()
        core = null
        scope.cancel()
    }

    /** Lock the current session and return to the login screen. Identity files are preserved. */
    fun logout() {
        DesktopCallLog.info("desktop logout initiated")
        pollJob?.cancel()
        pollJob = null
        clearUnlockedPatternCells()
        webRtcBridge?.dispose()
        webRtcBridge = null
        _videoCallState.value = DesktopVideoCallUiState()
        syncReceiveServer?.stop()
        syncReceiveServer = null
        core?.close()
        core = null
        val pinEnabled = identityStore.isPinEnabled()
        _state.update { current ->
            DesktopSessionUiState(
                authMode = if (pinEnabled) DesktopAuthMode.PinLogin else DesktopAuthMode.PatternLogin,
                initialized = false,
                pinEnabled = pinEnabled,
                storedDisplayName = identityStore.loadIdentityMeta()?.displayName,
                themeMode = current.themeMode,
                dataDirPath = current.dataDirPath,
                logFilePath = current.logFilePath,
                customBootstrapUrl = current.customBootstrapUrl,
                customBootstrapPublicKey = current.customBootstrapPublicKey,
            )
        }
    }

    /** Delete stored identity and go to the registration screen to create a new user. */
    fun createNewUser() {
        DesktopCallLog.info("desktop create-new-user: wiping identity files")
        pollJob?.cancel()
        pollJob = null
        clearUnlockedPatternCells()
        webRtcBridge?.dispose()
        webRtcBridge = null
        _videoCallState.value = DesktopVideoCallUiState()
        syncReceiveServer?.stop()
        syncReceiveServer = null
        core?.close()
        core = null
        identityStore.deleteAll()
        _state.update { current ->
            DesktopSessionUiState(
                authMode = DesktopAuthMode.Register,
                initialized = false,
                themeMode = current.themeMode,
                dataDirPath = current.dataDirPath,
                logFilePath = current.logFilePath,
                customBootstrapUrl = current.customBootstrapUrl,
                customBootstrapPublicKey = current.customBootstrapPublicKey,
            )
        }
    }

    private suspend fun bindCore(
        instance: DesktopAdaCore,
        displayName: String,
        peerId: String,
        unlockedCells: ByteArray,
    ) {
        pollJob?.cancel()
        webRtcBridge?.dispose()
        webRtcBridge = null
        _videoCallState.value = DesktopVideoCallUiState()
        core?.close()
        core = instance
        DesktopCallLog.info("desktop core bound; peer=${shortPeerId(peerId)}")
        replaceUnlockedPatternCells(unlockedCells)

        val aliases = contactAliases()
        val conversations = applyContactAliases(parseConversations(instance.getConversationsJson()), aliases)
        val activeConversationId = conversations.firstOrNull()?.id
        val messages = activeConversationId
            ?.let { conversationId ->
                applyMessageDisplayNames(
                    messages = parseMessages(instance.getMessagesJson(conversationId, 100)),
                    activeConversation = conversations.firstOrNull { it.id == conversationId },
                    aliases = aliases,
                )
            }
            ?: emptyList()
        val runtimeSnapshot = readRuntimeSnapshot(instance, conversations)

        identityStore.saveIdentityMeta(
            peerId = peerId,
            displayName = displayName,
            connectionProfile = runtimeSnapshot.bridgeStatus?.connectionProfile
                ?: identityStore.loadIdentityMeta()?.connectionProfile
                ?: DEFAULT_DESKTOP_CONNECTION_PROFILE,
        )

        _state.value = _state.value.copy(
            authMode = if (identityStore.isPinEnabled()) DesktopAuthMode.PinLogin else DesktopAuthMode.PatternLogin,
            initialized = true,
            patternLoading = false,
            patternError = null,
            pinEnabled = identityStore.isPinEnabled(),
            storedDisplayName = displayName,
            displayName = displayName,
            peerId = peerId,
            conversations = conversations,
            activeConversationId = activeConversationId,
            messages = messages,
            contactCardJson = runtimeSnapshot.contactCardJson,
            contactShareLink = runtimeSnapshot.contactShareLink,
            bridgeStatus = runtimeSnapshot.bridgeStatus,
            censorshipLevel = runtimeSnapshot.censorshipLevel,
            runtimeMetricsJson = runtimeSnapshot.metricsJson,
            callHistory = runtimeSnapshot.callHistory,
            callAvailability = runtimeSnapshot.callAvailability,
            activeCalls = runtimeSnapshot.activeCalls,
            incomingCall = null,
            draft = "",
            sendError = null,
            actionMessage = null,
            actionMessageIsError = false,
        )

        ensureSyncReceiveServerRunning(instance)
        startEventPolling()
    }

    /**
     * If this device was previously paired with a phone (link key exists in KV)
     * and the sync server is not already running, bind a new [SyncReceiveServer]
     * and record its port.  Safe to call multiple times — it is a no-op when the
     * server is already up.
     */
    private fun ensureSyncReceiveServerRunning(c: DesktopAdaCore) {
        if (syncReceiveServer != null) return          // already running
        c.getLinkKeyHex() ?: return                   // not paired, nothing to do
        val syncServer = SyncReceiveServer(
            linkKeyProvider = { c.getLinkKeyHex() },
            onSyncPush = { linkKeyHex, dataB64 ->
                val ok = c.handleSyncPush(linkKeyHex, dataB64)
                if (!ok) DesktopCallLog.warn("SyncReceiveServer: handleSyncPush returned false")
            },
            onError = { msg -> DesktopCallLog.warn("SyncReceiveServer: $msg") },
        )
        try {
            syncServer.start()
            syncReceiveServer = syncServer
            DesktopCallLog.info("SyncReceiveServer auto-started on port ${syncServer.port}")
        } catch (e: Exception) {
            DesktopCallLog.warn("SyncReceiveServer auto-start failed: ${e.message}")
        }
    }

    private fun startEventPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val activeCore = core ?: break
                val event = activeCore.pollEventJson(250)
                if (event != null) {
                    handleRuntimeEvent(event)
                    while (true) {
                        val queuedEvent = activeCore.pollEventJson(0) ?: break
                        handleRuntimeEvent(queuedEvent)
                    }
                    refreshFromCore()
                }
            }
        }
    }

    private suspend fun refreshFromCore(
        preferredConversationId: String? = _state.value.activeConversationId,
        ensuredConversation: DesktopConversationItem? = null,
    ) {
        val activeCore = core ?: return
        val aliases = contactAliases()
        val baseConversations = parseConversations(activeCore.getConversationsJson())
        val conversations = applyContactAliases(
            ensuredConversation
            ?.takeIf { candidate -> baseConversations.none { it.id == candidate.id } }
            ?.let { listOf(it) + baseConversations }
            ?: baseConversations,
            aliases,
        )
        val activeConversationId = preferredConversationId
            ?.takeIf { wanted -> conversations.any { it.id == wanted } }
            ?: conversations.firstOrNull()?.id
        val messages = activeConversationId
            ?.let { conversationId ->
                applyMessageDisplayNames(
                    messages = parseMessages(activeCore.getMessagesJson(conversationId, 100)),
                    activeConversation = conversations.firstOrNull { it.id == conversationId },
                    aliases = aliases,
                )
            }
            ?: emptyList()
        val runtimeSnapshot = readRuntimeSnapshot(activeCore, conversations)

        _state.update { current ->
            current.copy(
                pinEnabled = identityStore.isPinEnabled(),
                conversations = conversations,
                activeConversationId = activeConversationId,
                messages = messages,
                contactCardJson = runtimeSnapshot.contactCardJson,
                contactShareLink = runtimeSnapshot.contactShareLink,
                bridgeStatus = runtimeSnapshot.bridgeStatus,
                censorshipLevel = runtimeSnapshot.censorshipLevel,
                runtimeMetricsJson = runtimeSnapshot.metricsJson,
                callHistory = runtimeSnapshot.callHistory,
                callAvailability = runtimeSnapshot.callAvailability ?: current.callAvailability,
                activeCalls = runtimeSnapshot.activeCalls,
                incomingCall = refreshIncomingCallDisplayName(current.incomingCall, conversations, aliases),
                displayName = activeCore.getDisplayName() ?: current.displayName,
                peerId = activeCore.getPeerId() ?: current.peerId,
            )
        }
    }

    private fun setPatternError(message: String) {
        _state.update {
            it.copy(
                patternLoading = false,
                patternError = message,
            )
        }
    }

    private fun loginWithStoredCells(
        stored: StoredIdentityMeta,
        cells: ByteArray,
        authMode: DesktopAuthMode,
        invalidCredentialMessage: String,
    ) {
        scope.launch {
            _state.update {
                it.copy(
                    authMode = authMode,
                    storedDisplayName = stored.displayName,
                    patternLoading = true,
                    patternError = null,
                    sendError = null,
                )
            }

            try {
                val effectiveProfile = preferredDesktopConnectionProfile(stored.connectionProfile)
                DesktopAdaCore.initTracing(identityStore.coreDataDir.toString())
                val instance = DesktopAdaCore.createFromPattern(
                    cells = cells,
                    displayName = stored.displayName,
                    dataDir = identityStore.coreDataDir.toString(),
                    connectionProfile = effectiveProfile,
                ) ?: run {
                    setPatternError("Не удалось открыть локальный профиль.")
                    return@launch
                }

                val derivedPeerId = instance.getPeerId().orEmpty()
                if (derivedPeerId != stored.peerId) {
                    instance.close()
                    setPatternError(invalidCredentialMessage)
                    return@launch
                }

                if (effectiveProfile != stored.connectionProfile) {
                    identityStore.updateConnectionProfile(effectiveProfile)
                    DesktopCallLog.info(
                        "desktop connection profile migrated on stored login: ${stored.connectionProfile} -> $effectiveProfile",
                    )
                }

                bindCore(instance, stored.displayName, derivedPeerId, cells)
            } catch (error: Throwable) {
                DesktopCallLog.warn("desktop stored-cells login failed", error)
                setPatternError(error.message ?: "Не удалось открыть native ada_core runtime.")
            }
        }
    }

    private fun setActionMessage(message: String, isError: Boolean = false) {
        _state.update {
            it.copy(
                actionMessage = message,
                actionMessageIsError = isError,
            )
        }
    }

    private fun normalizePin(pin: String): String? {
        val normalized = pin.trim()
        return normalized.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    private fun preferredDesktopConnectionProfile(profile: String?): String {
        val normalized = normalizeConnectionProfile(profile)
        return if (normalized == "auto") {
            DEFAULT_DESKTOP_CONNECTION_PROFILE
        } else {
            normalized
        }
    }

    private fun copyUnlockedPatternCells(): ByteArray? = synchronized(this) {
        unlockedPatternCells?.copyOf()
    }

    private fun replaceUnlockedPatternCells(cells: ByteArray) {
        synchronized(this) {
            unlockedPatternCells?.fill(0)
            unlockedPatternCells = cells.copyOf()
        }
    }

    private fun clearUnlockedPatternCells() {
        synchronized(this) {
            unlockedPatternCells?.fill(0)
            unlockedPatternCells = null
        }
    }

    private fun readRuntimeSnapshot(
        activeCore: DesktopAdaCore,
        conversations: List<DesktopConversationItem>,
    ): RuntimeSnapshot {
        val contactCardJson = activeCore.getContactCardJson()
        return RuntimeSnapshot(
            contactCardJson = contactCardJson,
            contactShareLink = contactCardJson?.let { json ->
                runCatching { DesktopAdaCore.encodeContactShortLink(json) }.getOrNull()
            },
            bridgeStatus = parseBridgeStatus(activeCore.getBridgeStatusJson()),
            censorshipLevel = parseCensorshipLevel(activeCore.detectCensorshipJson()),
            metricsJson = activeCore.getMetricsSnapshot(),
            callAvailability = parseCallAvailability(activeCore.getCallAvailabilityJson()),
            activeCalls = parseActiveCalls(activeCore.getActiveCallsJson(), conversations),
            callHistory = parseCallHistory(activeCore.getCallHistoryJson(200), conversations),
        )
    }

    private fun contactAliases(): Map<String, String> =
        preferencesStore.contactAliases().filterValues { it.isNotBlank() }

    private fun applyContactAliases(
        conversations: List<DesktopConversationItem>,
        aliases: Map<String, String>,
    ): List<DesktopConversationItem> = conversations.map { conversation ->
        if (!conversation.isDirect) {
            conversation
        } else {
            aliases[conversation.id.removePrefix("d:")]
                ?.takeIf { it.isNotBlank() }
                ?.let { alias -> conversation.copy(displayName = alias) }
                ?: conversation
        }
    }

    private fun applyMessageDisplayNames(
        messages: List<DesktopChatMessage>,
        activeConversation: DesktopConversationItem?,
        aliases: Map<String, String>,
    ): List<DesktopChatMessage> {
        val directPeerId = activeConversation
            ?.takeIf { it.isDirect }
            ?.id
            ?.removePrefix("d:")
        val directDisplayName = activeConversation
            ?.takeIf { it.isDirect }
            ?.displayName

        return messages.map { message ->
            if (message.isMine) {
                message
            } else {
                val resolvedName = aliases[message.sender]
                    ?.takeIf { it.isNotBlank() }
                    ?: if (directPeerId == message.sender) {
                        directDisplayName
                    } else {
                        null
                    }
                    ?: message.senderName.takeIf { it.isNotBlank() }
                    ?: shortPeerId(message.sender)
                message.copy(senderName = resolvedName)
            }
        }
    }

    private fun activeDirectPeerId(): String? =
        _state.value.activeConversationId
            ?.takeIf { it.startsWith("d:") }
            ?.removePrefix("d:")

    private fun mediaBridge(): DesktopWebRtcBridge =
        webRtcBridge ?: DesktopWebRtcBridge(
            coreProvider = { core },
            onError = { message -> setActionMessage(message, isError = true) },
            onVideoStateChanged = { videoState -> _videoCallState.value = videoState },
        ).also { bridge ->
            webRtcBridge = bridge
        }

    private fun startDesktopCall(kindLabel: String, hasVideo: Boolean) {
        val peerId = activeDirectPeerId()
        if (peerId == null) {
            setActionMessage("Звонки доступны только в direct chat.", isError = true)
            return
        }

        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val availability = parseCallAvailability(activeCore.getCallAvailabilityJson())
            if (availability != null) {
                _state.update { it.copy(callAvailability = availability) }
            }

            when {
                availability == null -> {
                    DesktopCallLog.warn("desktop call start preflight missing for peer=${shortPeerId(peerId)}")
                    setActionMessage("Не удалось прочитать call preflight из runtime.", isError = true)
                }

                !availability.available -> {
                    DesktopCallLog.warn(
                        "desktop call preflight rejected peer=${shortPeerId(peerId)} detail=${availability.detail}",
                    )
                    setActionMessage(availability.detail, isError = true)
                }

                else -> {
                    val callStart = mediaBridge().startOutgoingCall(peerId, hasVideo)
                    if (callStart.success) {
                        refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                        DesktopCallLog.info(
                            "desktop call started peer=${shortPeerId(peerId)} video=$hasVideo callId=${callStart.callId}",
                        )
                        setActionMessage("$kindLabel с ${shortPeerId(peerId)} запущен. Desktop media engine активен.")
                    } else {
                        refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                        DesktopCallLog.warn(
                            "desktop call start failed peer=${shortPeerId(peerId)} video=$hasVideo reason=${callStart.error}",
                        )
                        setActionMessage(callStart.error ?: "$kindLabel не удалось запустить.", isError = true)
                    }
                }
            }
        }
    }

    private suspend fun handleRuntimeEvent(eventJson: String) {
        val raw = runCatching { JSONObject(eventJson) }.getOrNull() ?: return
        when (raw.optString("type")) {
            "BlobAvailable" -> {
                val fileId = raw.optString("file_id", "")
                val from = raw.optString("from", "")
                val hash = raw.optString("hash", "")
                if (fileId.isNotBlank() && from.isNotBlank() && hash.isNotBlank()) {
                    blobDownloadMap[fileId] = BlobDownloadInfo(fromPeerB64 = from, hashHex = hash)
                    _state.update { current ->
                        current.copy(downloadableFileIds = current.downloadableFileIds + fileId)
                    }
                }
            }

            "TransferEvent" -> {
                val event = raw.optString("event", "")
                if (event == "completed") {
                    val transferId = raw.optString("transfer_id", "")
                    if (transferId.isNotBlank()) {
                        completedTransferFileIds.add(transferId)
                        _state.update { current ->
                            current.copy(downloadableFileIds = current.downloadableFileIds + transferId)
                        }
                    }
                }
                if (event == "completed" || event == "failed" || event == "cancelled") {
                    refreshFromCore(preferredConversationId = _state.value.activeConversationId)
                }
            }

            "IncomingCall" -> {
                val incoming = parseIncomingCallEvent(
                    json = eventJson,
                    conversations = _state.value.conversations,
                    aliases = contactAliases(),
                ) ?: return

                if (hasConflictingCallForIncoming(incoming)) {
                    core?.declineCall(incoming.callId, incoming.peerId)
                    setActionMessage(
                        "Входящий звонок от ${incoming.displayName} отклонён: на desktop уже есть активный звонок.",
                        isError = true,
                    )
                    return
                }

                _state.update { it.copy(incomingCall = incoming) }
                setActionMessage(
                    if (incoming.groupId != null || incoming.callSessionId != null) {
                        "Входящий групповой звонок от ${incoming.displayName}. На desktop пока можно только отклонить вызов."
                    } else if (incoming.hasVideo) {
                        "Входящий видеозвонок от ${incoming.displayName}. На desktop можно принять или отклонить вызов."
                    } else {
                        "Входящий звонок от ${incoming.displayName}. На desktop можно принять или отклонить вызов."
                    },
                )
            }

            "IceCandidate" -> {
                webRtcBridge?.onRemoteIceCandidate(
                    callIdHex = raw.optString("call_id", ""),
                    peerIdB64 = raw.optString("peer", ""),
                    candidate = raw.optString("candidate", ""),
                    sdpMid = raw.optString("sdp_mid", "").ifBlank { null },
                    sdpMLineIndex = raw.optInt("sdp_mline_index", 0),
                )
            }

            "IceRestartOffer" -> {
                webRtcBridge?.acceptIceRestartOffer(
                    callIdHex = raw.optString("call_id", ""),
                    peerIdB64 = raw.optString("peer", ""),
                    offerSdp = raw.optString("offer_sdp", ""),
                )
            }

            "IceRestartAnswer" -> {
                webRtcBridge?.onIceRestartAnswer(
                    callIdHex = raw.optString("call_id", ""),
                    answerSdp = raw.optString("answer_sdp", ""),
                )
            }

            "CallStateChanged" -> {
                val callId = raw.optString("call_id", "")
                val stateName = raw.optString("state", "")
                if (stateName == "Active") {
                    val answerSdp = raw.optString("answer_sdp", "")
                    if (callId.isNotBlank() && answerSdp.isNotBlank()) {
                        webRtcBridge?.onRemoteAnswerReceived(callId, answerSdp)
                    }
                    clearIncomingCall(callId)
                }
                if (stateName == "Ended" || stateName == "Failed") {
                    webRtcBridge?.onCallEnded(
                        callIdHex = callId.ifBlank { null },
                        peerIdB64 = raw.optString("peer", "").ifBlank { null },
                    )
                    val displayName = resolveCallDisplayName(
                        peerId = raw.optString("peer", ""),
                        groupId = raw.optString("group_id", "").ifBlank { null },
                        conversations = _state.value.conversations,
                        aliases = contactAliases(),
                    )
                    _state.update { current ->
                        current.copy(
                            incomingCall = current.incomingCall?.takeUnless { incoming -> incoming.callId == callId },
                        )
                    }
                    val subject = displayName.takeIf { it.isNotBlank() }?.let { "с $it" } ?: ""
                    setActionMessage(
                        if (stateName == "Failed") {
                            "Звонок $subject завершился с ошибкой.".replace("  ", " ").trim()
                        } else {
                            "Звонок $subject завершён.".replace("  ", " ").trim()
                        },
                        isError = stateName == "Failed",
                    )
                }
            }
        }
    }

    private fun hasConflictingCallForIncoming(incoming: DesktopIncomingCall): Boolean {
        val current = _state.value
        if (current.incomingCall?.callId?.takeIf { it != incoming.callId } != null) {
            return true
        }
        return current.activeCalls.any { active ->
            active.callId.isBlank() || active.callId != incoming.callId
        }
    }

    private fun clearIncomingCall(callId: String) {
        if (callId.isBlank()) return
        _state.update { current ->
            current.copy(
                incomingCall = current.incomingCall?.takeUnless { incoming -> incoming.callId == callId },
            )
        }
    }

    private fun refreshIncomingCallDisplayName(
        incomingCall: DesktopIncomingCall?,
        conversations: List<DesktopConversationItem>,
        aliases: Map<String, String>,
    ): DesktopIncomingCall? {
        if (incomingCall == null) return null
        val displayName = resolveCallDisplayName(
            peerId = incomingCall.peerId,
            groupId = incomingCall.groupId,
            conversations = conversations,
            aliases = aliases,
        )
        return if (displayName == incomingCall.displayName) {
            incomingCall
        } else {
            incomingCall.copy(displayName = displayName)
        }
    }

    private fun resolveCallDisplayName(
        peerId: String,
        groupId: String?,
        conversations: List<DesktopConversationItem>,
        aliases: Map<String, String>,
    ): String = when {
        !groupId.isNullOrBlank() -> conversations.firstOrNull { it.id == "g:$groupId" }?.displayName
            ?: "Групповой звонок"
        peerId.isBlank() -> "Неизвестный контакт"
        aliases[peerId].isNullOrBlank().not() -> aliases[peerId].orEmpty()
        else -> conversations.firstOrNull { it.id == "d:$peerId" }?.displayName
            ?: shortPeerId(peerId)
    }

    private fun detectMimeType(path: java.nio.file.Path): String {
        val detected = runCatching { Files.probeContentType(path) }
            .getOrNull()
            ?.trim()
            .orEmpty()
        if (detected.isNotBlank()) {
            return detected
        }

        return when (path.fileName?.toString().orEmpty().substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun parseContactImportInput(input: String): String? {
        val normalized = input.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()

        return when {
            normalized.startsWith("ada://s/") -> DesktopAdaCore.decodeContactShortLink(normalized)
            normalized.startsWith("ada://add-contact") -> decodeLegacyContactLink(normalized)
            normalized.startsWith("{") -> runCatching {
                JSONObject(normalized).getString("id")
                normalized
            }.getOrNull()
            else -> null
        }
    }

    private fun parseBridgeManifestImportInput(
        input: String,
        trustedPublicKeyHex: String? = null,
        sourceHint: String = "manual",
    ): PendingBridgeManifestImport? {
        val normalized = input.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
        val fallbackKey = normalizeManifestPublicKeyHex(trustedPublicKeyHex)

        if (normalized.startsWith("ada://bridge-manifest")) {
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            if (uri.scheme != "ada" || uri.host != "bridge-manifest") {
                return null
            }

            val params = parseQueryParams(uri.rawQuery.orEmpty())
            val linkKey = normalizeManifestPublicKeyHex(params["key"]) ?: fallbackKey
            val encodedData = params["data"]?.takeIf { it.isNotBlank() }
                ?: params["manifest"]?.takeIf { it.isNotBlank() }
            val manifestUrl = params["url"]?.trim().orEmpty()

            if (manifestUrl.startsWith("https://") || manifestUrl.startsWith("http://")) {
                return PendingBridgeManifestImport(
                    manifestUrl = manifestUrl,
                    trustedPublicKeyHex = linkKey,
                    source = manifestUrl,
                    noticeLabel = sourceLabelForManifestUrl(manifestUrl),
                )
            }

            if (!encodedData.isNullOrBlank()) {
                val manifestJson = runCatching {
                    String(Base64.getUrlDecoder().decode(encodedData), Charsets.UTF_8)
                }.getOrNull() ?: return null

                return PendingBridgeManifestImport(
                    manifestJson = manifestJson,
                    trustedPublicKeyHex = linkKey,
                    source = "deeplink",
                    noticeLabel = "QR/deeplink",
                )
            }

            return null
        }

        if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
            return PendingBridgeManifestImport(
                manifestUrl = normalized,
                trustedPublicKeyHex = fallbackKey,
                source = normalized,
                noticeLabel = sourceLabelForManifestUrl(normalized),
            )
        }

        if (!normalized.startsWith("{")) {
            return null
        }

        val manifestJson = runCatching {
            val obj = JSONObject(normalized)
            if (!obj.has("payload_json") || !obj.has("signature_hex")) {
                null
            } else {
                normalized
            }
        }.getOrNull() ?: return null

        return PendingBridgeManifestImport(
            manifestJson = manifestJson,
            trustedPublicKeyHex = fallbackKey,
            source = sourceHint,
            noticeLabel = sourceHint,
        )
    }

    private fun importBridgeManifestPayload(payload: PendingBridgeManifestImport) {
        scope.launch {
            val activeCore = core
            if (activeCore == null) {
                setActionMessage("Desktop runtime ещё не инициализирован.", isError = true)
                return@launch
            }

            val trustedKey = payload.trustedPublicKeyHex.orEmpty()
            val result = when {
                payload.manifestJson != null -> activeCore.importBridgeManifestJsonDetailed(
                    payload.manifestJson,
                    payload.source,
                    trustedKey,
                )

                payload.manifestUrl != null -> activeCore.importBridgeManifestUrlDetailed(
                    payload.manifestUrl,
                    trustedKey,
                )

                else -> DesktopBridgeOperationResult(false, "missing bridge manifest payload")
            }

            if (result.success) {
                if (payload.persistBootstrap) {
                    preferencesStore.saveCustomBridgeBootstrap(payload.manifestUrl.orEmpty(), trustedKey)
                    _state.update {
                        it.copy(
                            customBootstrapUrl = payload.manifestUrl.orEmpty(),
                            customBootstrapPublicKey = trustedKey,
                        )
                    }
                }
                refreshFromCore()
                setActionMessage("Bridge manifest импортирован: ${payload.noticeLabel}")
            } else {
                setActionMessage(formatBridgeFailure(result.error), isError = true)
            }
        }
    }

    private fun decodeLegacyContactLink(input: String): String? {
        val encoded = parseQueryParams(input.substringAfter('?', ""))["card"].orEmpty()
        if (encoded.isBlank()) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> =
        rawQuery
            .split('&')
            .mapNotNull { part ->
                if (part.isBlank()) {
                    null
                } else {
                    val pieces = part.split('=', limit = 2)
                    val key = URLDecoder.decode(pieces[0], Charsets.UTF_8.name())
                    val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
                    key to value
                }
            }
            .toMap()

    private fun normalizeManifestPublicKeyHex(value: String?): String? {
        val normalized = value?.trim()?.replace(":", "")?.uppercase().orEmpty()
        return normalized.ifBlank { null }
    }

    private fun sourceLabelForManifestUrl(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }

    private fun formatBridgeFailure(error: String?): String {
        val reason = error
            ?.trim()
            ?.removePrefix("Bridge error: ")
            ?.takeIf { it.isNotBlank() }
            ?.take(240)
        return if (reason == null) {
            "Не удалось импортировать bridge manifest."
        } else {
            "Не удалось импортировать bridge manifest: $reason"
        }
    }

    private fun parseContactIdentity(contactJson: String): ParsedContactIdentity? = runCatching {
        val obj = JSONObject(contactJson)
        val peerId = obj.getString("id")
        val displayName = obj.optString("name", obj.optString("display_name", "")).trim().ifBlank { null }
        ParsedContactIdentity(peerId, displayName)
    }.getOrNull()

    private fun formatConnectionProfile(profile: String): String = when (profile) {
        "normal" -> "Normal"
        "mobile_saver" -> "Mobile Saver"
        "censored_light" -> "Censored Light"
        "censored_heavy" -> "Censored Heavy"
        "allowlist_only" -> "Allowlist Only"
        "incident_safe" -> "Incident Safe"
        else -> "Auto"
    }

    private fun parseCensorshipLevel(json: String): String = runCatching {
        JSONObject(json).optString("level", "None")
    }.getOrDefault("None")

    private fun shortPeerId(peerId: String): String =
        if (peerId.length <= 12) peerId else peerId.take(8) + "…" + peerId.takeLast(4)

    private data class ParsedContactIdentity(
        val id: String,
        val displayName: String?,
    )
}