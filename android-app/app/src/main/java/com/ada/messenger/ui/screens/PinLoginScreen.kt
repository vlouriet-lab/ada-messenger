package com.ada.messenger.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pattern
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.AppLockManager
import com.ada.messenger.core.PinCheckResult
import com.ada.messenger.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick-unlock screen shown when the user returns to the app after initial setup.
 * - Shows a 4-digit PIN pad.
 * - "Enter pattern instead" link navigates to the full pattern screen.
 *
 * @param onSuccess       Called after successful unlock (navigate to main).
 * @param onUsePattern    Called when user wants to fall back to the pattern screen.
 */
@Composable
fun PinLoginScreen(
    viewModel: AdaCoreViewModel,
    onSuccess: () -> Unit,
    onUsePattern: () -> Unit,
    reauthMode: Boolean = false,
) {
    val context   = LocalContext.current
    val haptic    = LocalHapticFeedback.current
    val scope     = rememberCoroutineScope()
    val appLock   = viewModel.appLock

    val initialized   by viewModel.initialized.collectAsState()
    val patternLoading by viewModel.patternLoading.collectAsState()
    val patternError  by viewModel.patternError.collectAsState()

    var pin      by remember { mutableStateOf("") }
    val maxPin   = 4
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var shakeErr by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }

    // C-NEW-1: Brute-force lockout state — backed by persistent AppLockManager (survives process death)
    var lockoutSeconds by remember { mutableStateOf(0) }

    // Navigate away once core is loaded
    LaunchedEffect(initialized, reauthMode) {
        if (!reauthMode && initialized) onSuccess()
    }

    // Surface ViewModel errors (wrong cells from decryption)
    LaunchedEffect(patternError) {
        if (patternError != null) {
            errorMsg = patternError
            shakeErr = true
            pin = ""
        }
    }
    LaunchedEffect(shakeErr) {
        if (shakeErr) { kotlinx.coroutines.delay(500); shakeErr = false }
    }

    // Countdown for brute-force lockout display (reads persisted lockedUntilMs on each recomposition)
    LaunchedEffect(Unit) {
        val lockedUntil = appLock.pinLockedUntilMs
        if (lockedUntil > System.currentTimeMillis()) {
            while (System.currentTimeMillis() < appLock.pinLockedUntilMs) {
                lockoutSeconds = ((appLock.pinLockedUntilMs - System.currentTimeMillis() + 999L) / 1000L).toInt().coerceAtLeast(0)
                kotlinx.coroutines.delay(500L)
            }
            lockoutSeconds = 0
        }
    }

    // Shake offset animation for error
    val shakeOffset by animateFloatAsState(
        targetValue = if (shakeErr) 1f else 0f,
        animationSpec = if (shakeErr) {
            keyframes {
                durationMillis = 400
                0f  at 0
                -12f at 50
                12f  at 100
                -12f at 150
                12f  at 200
                -6f  at 280
                6f   at 340
                0f   at 400
            }
        } else spring(), label = "shake"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(R.string.pin_login_prompt),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(24.dp))

        // Dots indicator
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.offset(x = shakeOffset.dp),
        ) {
            repeat(4) { idx ->
                val filled = idx < pin.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f)
                        )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Error message
        AnimatedErrorText(errorMsg)

        Spacer(Modifier.height(28.dp))

        // PIN pad
        PinPad(
            onDigit = { d ->
                if (!isUnlocking && pin.length < maxPin) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    pin += d
                    errorMsg = null
                    if (pin.length == 4) {
                        // Try to unlock
                        scope.launch {
                            isUnlocking = true
                            // C-NEW-1: Check persisted lockout before each attempt
                            if (System.currentTimeMillis() < appLock.pinLockedUntilMs) {
                                errorMsg = context.getString(R.string.error_pin_too_many_attempts) + "${lockoutSeconds}\u0441."
                                pin = ""
                                isUnlocking = false
                                return@launch
                            }
                            val checkResult = withContext(Dispatchers.IO) {
                                appLock.checkPinWithCells(pin)
                            }
                            when (checkResult.result) {
                                PinCheckResult.REAL -> {
                                    val cells = checkResult.realCells
                                    if (cells == null) {
                                        val delaySec = withContext(Dispatchers.IO) { appLock.recordFailedPinAttempt() }
                                        val attempts = appLock.failedPinAttempts                                          
                                          // Roadmap 2.0: Self-Destruct on 10 failed attempts
                                          if (attempts >= 10) {
                                              viewModel.executeKillCode()
                                              return@launch
                                          }
                                        errorMsg = if (delaySec > 0) {
                                            lockoutSeconds = delaySec.toInt()
                                            context.getString(R.string.error_pin_too_many_attempts) + "${delaySec}\u0441."
                                        } else {
                                            context.getString(R.string.error_pin_incorrect_attempt, attempts)
                                        }
                                        shakeErr = true
                                        pin = ""
                                    } else {
                                        withContext(Dispatchers.IO) { appLock.resetFailedPinAttempts() }
                                        if (reauthMode) {
                                            val verified = try {
                                                viewModel.verifyPattern(cells)
                                            } finally {
                                                cells.fill(0)
                                            }
                                            if (verified) {
                                                onSuccess()
                                            } else {
                                                errorMsg = context.getString(R.string.error_pattern_invalid)
                                                shakeErr = true
                                                pin = ""
                                            }
                                        } else {
                                            viewModel.loginWithCells(cells)
                                        }
                                    }
                                }
                                PinCheckResult.CLEAN -> {
                                    withContext(Dispatchers.IO) { appLock.resetFailedPinAttempts() }
                                    viewModel.enterCleanMode()
                                }
                                PinCheckResult.KILL -> {
                                    withContext(Dispatchers.IO) { appLock.resetFailedPinAttempts() }
                                    viewModel.executeKillCode()
                                }
                                PinCheckResult.NONE -> {
                                    val delaySec = withContext(Dispatchers.IO) { appLock.recordFailedPinAttempt() }
                                    val attempts = appLock.failedPinAttempts                                      
                                      // Roadmap 2.0: Self-Destruct on 10 failed attempts
                                      if (attempts >= 10) {
                                          viewModel.executeKillCode()
                                          return@launch
                                      }
                                    errorMsg = if (delaySec > 0) {
                                        lockoutSeconds = delaySec.toInt()
                                        context.getString(R.string.error_pin_too_many_attempts) + "${delaySec}\u0441."
                                    } else {
                                        context.getString(R.string.error_pin_incorrect_attempt, attempts)
                                    }
                                    shakeErr = true
                                    pin = ""
                                }
                            }
                            isUnlocking = false
                        }
                    }
                }
            },
            onBackspace = {
                if (!isUnlocking && pin.isNotEmpty()) pin = pin.dropLast(1)
            },
        )

        Spacer(Modifier.height(24.dp))

        if (patternLoading || isUnlocking) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = {
                viewModel.clearPatternError()
                errorMsg = null
                onUsePattern()
            },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Outlined.Pattern, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pin_login_use_pattern))
        }

        Spacer(Modifier.weight(1f))
    }
}

// ── PIN pad ───────────────────────────────────────────────────────────────────

@Composable
private fun PinPad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { digit ->
                    PinKey(label = digit, onClick = { onDigit(digit) })
                }
            }
        }
        // Bottom row: empty | 0 | backspace
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PinKeyEmpty()
            PinKey(label = "0", onClick = { onDigit("0") })
            PinKeyAction(label = "⌫", onClick = onBackspace)
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PinKeyAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun PinKeyIcon(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun PinKeyEmpty() {
    Box(modifier = Modifier.size(72.dp))
}

@Composable
private fun AnimatedErrorText(msg: String?) {
    val alpha by animateFloatAsState(
        targetValue = if (msg != null) 1f else 0f,
        label = "errAlpha",
    )
    Text(
        text = msg ?: "",
        color = MaterialTheme.colorScheme.error.copy(alpha = alpha),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}
