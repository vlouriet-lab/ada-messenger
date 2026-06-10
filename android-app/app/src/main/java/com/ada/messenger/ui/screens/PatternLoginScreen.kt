package com.ada.messenger.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.R
import androidx.compose.ui.res.stringResource
import com.ada.messenger.ui.components.PATTERN_CUBES
import com.ada.messenger.ui.components.PatternBoardView
import com.ada.messenger.ui.components.cycleCell
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen shown when the user opens the app on a **new device** and needs to
 * prove their identity by recreating the original pattern.
 *
 * Flow:
 *   1. User sees "Enter your pattern" + empty 8×8 grid
 *   2. User taps 16 cells
 *   3. Taps "Verify"
 *   4a. Match → `onSuccess()` is called
 *   4b. Mismatch → shake animation + error hint, try again
 *
 * @param viewModel  Shared [AdaCoreViewModel] that already has a loaded identity
 *                   (loaded from `ada_core_create` with passphrase, before this screen
 *                    is shown — the verify step checks the derived peer_id).
 * @param onSuccess  Called when pattern is verified successfully.
 * @param onForgot   Called when user taps "I forgot my pattern" — could show recovery UI.
 */
@Composable
fun PatternLoginScreen(
    viewModel: AdaCoreViewModel,
    onSuccess: () -> Unit,
    onForgot: (() -> Unit)? = null,
    reauthMode: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val patternLoading by viewModel.patternLoading.collectAsState()
    val patternError   by viewModel.patternError.collectAsState()
    val initialized    by viewModel.initialized.collectAsState()

    // K1: use AppLockManager so lockout survives Force Stop / process death
    val appLock = viewModel.appLock

    var cells by remember { mutableStateOf(mapOf<Int, Int>()) }
    var shakeError by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loginRequested by remember { mutableStateOf(false) }

    // Read initial lockout state from persisted store
    var lockCountdown by remember {
        mutableStateOf(
            ((appLock.patternLockedUntilMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        )
    }

    // Countdown ticker — updates every second while locked
    LaunchedEffect(Unit) {
        while (true) {
            val remaining = (appLock.patternLockedUntilMs - System.currentTimeMillis()) / 1000L
            lockCountdown = remaining.coerceAtLeast(0L)
            if (lockCountdown <= 0L) break
            kotlinx.coroutines.delay(500L)
        }
    }

    // Navigate away once the core is loaded
    LaunchedEffect(initialized, reauthMode) {
        if (!reauthMode && initialized) {
            appLock.resetFailedPatternAttempts()
            onSuccess()
        }
    }

    // Drop stale errors left from PIN quick-unlock screen.
    LaunchedEffect(Unit) {
        viewModel.clearPatternError()
    }

    // Show ViewModel-level error (wrong pattern or corrupt data)
    LaunchedEffect(patternError) {
        if (patternError != null) {
            // Ignore cross-screen stale errors that were not produced by this screen action.
            if (!loginRequested) return@LaunchedEffect
            loginRequested = false
            shakeError = true
            cells = emptyMap()
            // Record failure in persistent store — survives process kill
            val delaySec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                appLock.recordFailedPatternAttempt()
            }
            val attempts = appLock.failedPatternAttempts
            if (attempts >= 10) {
                viewModel.executeKillCode()
                return@LaunchedEffect
            }
            errorMsg = if (delaySec > 0) {
                lockCountdown = delaySec
                // Restart countdown
                context.getString(R.string.error_pattern_too_many_attempts, delaySec.toInt())
            } else {
                val currentAttempts = appLock.failedPatternAttempts
                context.getString(R.string.error_pattern_incorrect_attempt, currentAttempts) + (patternError ?: "")
            }
        }
    }

    LaunchedEffect(shakeError) {
        if (shakeError) {
            kotlinx.coroutines.delay(500)
            shakeError = false
        }
    }

    // V-32: static gradient background
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.background,
        ),
        start = Offset(0f, 0f),
        end = Offset(500f, 500f),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(shimmerBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // Lock icon
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )

            Text(
                text = stringResource(R.string.pattern_login_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.pattern_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            PatternBoardView(
                selectedCells = cells,
                onCellTap = { idx ->
                    cells = cycleCell(cells, idx)
                    errorMsg = null
                },
                showCounter = true,
            )

            // Error / hint chip
            AnimatedVisibility(
                visible = errorMsg != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = errorMsg ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Forgot pattern link — shown only after many attempts AND no active lockout
            if (onForgot != null && appLock.failedPatternAttempts >= 8 && lockCountdown <= 0L) {
                TextButton(onClick = onForgot) {
                    Text(
                        text = stringResource(R.string.pattern_login_forgot),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { cells = emptyMap(); errorMsg = null },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.button_clear))
                }

                Button(
                    onClick = {
                        if (lockCountdown > 0L) return@Button  // locked out
                        if (cells.size != PATTERN_CUBES) {
                            shakeError = true
                            errorMsg = context.getString(R.string.error_pattern_wrong_cell_count)
                            return@Button
                        }
                        loginRequested = !reauthMode
                        scope.launch {
                            // Canonical 32-byte format: [idx, color, idx, color, ...]
                            val byteCells = cells.entries
                                .sortedBy { it.key }
                                .flatMap { listOf(it.key.toByte(), it.value.toByte()) }
                                .toByteArray()
                            if (reauthMode) {
                                val verified = try {
                                    viewModel.verifyPattern(byteCells)
                                } finally {
                                    byteCells.fill(0)
                                }
                                if (verified) {
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        appLock.resetFailedPatternAttempts()
                                    }
                                    onSuccess()
                                } else {
                                    shakeError = true
                                    cells = emptyMap()
                                    val delaySec = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        appLock.recordFailedPatternAttempt()
                                    }
                                    val attempts = appLock.failedPatternAttempts
                                    if (attempts >= 10) {
                                        viewModel.executeKillCode()
                                        return@launch
                                    }
                                    errorMsg = if (delaySec > 0) {
                                        lockCountdown = delaySec
                                        context.getString(R.string.error_pattern_too_many_attempts, delaySec.toInt())
                                    } else {
                                        context.getString(R.string.error_pattern_incorrect_attempt, attempts)
                                    }
                                }
                            } else {
                                // loginWithPattern re-derives identity deterministically and
                                // verifies peer_id matches stored value, then sets initialized=true
                                viewModel.loginWithPattern(byteCells)
                            }
                        }
                    },
                    enabled = cells.size == PATTERN_CUBES && !patternLoading && lockCountdown <= 0L,
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (patternLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else if (lockCountdown > 0L) {
                        Text(stringResource(R.string.pattern_login_lockout_seconds, lockCountdown.toInt()), fontSize = 15.sp)
                    } else {
                        Text(stringResource(R.string.button_login), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
