package com.ada.messenger.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.R
import androidx.compose.ui.res.stringResource
import com.ada.messenger.ui.components.PATTERN_CUBES
import com.ada.messenger.ui.components.PatternBoardView
import com.ada.messenger.ui.components.cycleCell

// ── Registration steps ─────────────────────────────────────────────────────────

private enum class RegStep {
    DRAW,       // User draws the pattern for the first time
    CONFIRM,    // User re-draws the pattern to confirm it
    NICKNAME,   // User enters a display name
    LOADING,    // Argon2id running, spinner shown
    DONE,       // Success
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Full-screen registration flow:
 *   1. Draw a unique 16-cell pattern on an 8×8 grid
 *   2. Re-draw it to confirm (must match)
 *   3. Enter a nickname
 *   4. Argon2id key derivation (progress indicator)
 *   5. Success → navigate to main
 *
 * @param viewModel   Shared [AdaCoreViewModel].
 * @param onFinished  Callback invoked when the identity is created and the core is ready.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PatternRegistrationScreen(
    viewModel: AdaCoreViewModel,
    onFinished: () -> Unit,
    onImportRecovery: (() -> Unit)? = null,
) {
    val initialized by viewModel.initialized.collectAsState()
    val patternLoading by viewModel.patternLoading.collectAsState()
    val patternError by viewModel.patternError.collectAsState()

    var step by remember { mutableStateOf(RegStep.DRAW) }

    // Cell colour maps for each step
    var firstPattern by remember { mutableStateOf(mapOf<Int, Int>()) }
    var confirmPattern by remember { mutableStateOf(mapOf<Int, Int>()) }
    var nickname by remember { mutableStateOf("") }

    var shakeError by remember { mutableStateOf(false) }
    var confirmMismatch by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    // If ViewModel survived app backgrounding (process not killed), skip straight to main
    LaunchedEffect(Unit) {
        if (initialized) onFinished()
    }
    // Navigate away once the core is ready
    LaunchedEffect(initialized) {
        if (initialized && step == RegStep.LOADING) {
            step = RegStep.DONE
        }
    }
    // If the ViewModel reports a pattern error while spinner is shown, go back to nickname step
    LaunchedEffect(patternError) {
        if (patternError != null && step == RegStep.LOADING) {
            step = RegStep.NICKNAME
        }
    }
    LaunchedEffect(step) {
        if (step == RegStep.DONE) {
            kotlinx.coroutines.delay(800)
            onFinished()
        }
    }

    // Reset shake after animation completes
    LaunchedEffect(shakeError) {
        if (shakeError) {
            kotlinx.coroutines.delay(500)
            shakeError = false
        }
    }

    // V-32: static gradient background
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
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
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith
                slideOutHorizontally { -it } + fadeOut()
            },
            label = "regStep"
        ) { currentStep ->
            when (currentStep) {
                RegStep.DRAW -> DrawStep(
                    title = stringResource(R.string.pattern_register_step1_title),
                    subtitle = stringResource(R.string.pattern_register_step1_subtitle),
                    cells = firstPattern,
                    onCellTap = { idx ->
                        val next = cycleCell(firstPattern, idx)
                        firstPattern = next
                        if (next.size > PATTERN_CUBES) shakeError = true
                    },
                    shakeError = shakeError,
                    onClear = { firstPattern = emptyMap() },
                    onNext = {
                        if (firstPattern.size == PATTERN_CUBES) {
                            step = RegStep.CONFIRM
                        } else {
                            shakeError = true
                        }
                    },
                    nextEnabled = firstPattern.size == PATTERN_CUBES,
                    nextLabel = stringResource(R.string.button_next),
                    hintText = stringResource(R.string.pattern_register_step1_hint),
                    secondaryActionLabel = onImportRecovery?.let {
                        stringResource(R.string.recovery_import_open_button)
                    },
                    onSecondaryAction = onImportRecovery,
                )

                RegStep.CONFIRM -> DrawStep(
                    title = stringResource(R.string.pattern_register_step2_title),
                    subtitle = stringResource(R.string.pattern_register_step2_subtitle),
                    cells = confirmPattern,
                    onCellTap = { idx ->
                        confirmPattern = cycleCell(confirmPattern, idx)
                        confirmMismatch = false
                    },
                    shakeError = shakeError,
                    onClear = { confirmPattern = emptyMap(); confirmMismatch = false },
                    onNext = {
                        if (confirmPattern == firstPattern) {
                            confirmMismatch = false
                            step = RegStep.NICKNAME
                        } else {
                            confirmMismatch = true
                            shakeError = true
                            confirmPattern = emptyMap()
                        }
                    },
                    nextEnabled = confirmPattern.size == PATTERN_CUBES,
                    nextLabel = stringResource(R.string.pattern_register_step2_next),
                    hintText = if (confirmMismatch)
                        stringResource(R.string.error_pattern_mismatch)
                    else
                        stringResource(R.string.error_pattern_mismatch_hint),
                    hintIsError = confirmMismatch,
                )

                RegStep.NICKNAME -> NicknameStep(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    loading = patternLoading,
                    error = patternError,
                    onRegister = {
                        focusManager.clearFocus()
                        if (nickname.trim().isNotEmpty() && nickname.trim().length <= 64) {
                            step = RegStep.LOADING
                            // Canonical 32-byte format: [idx, color, idx, color, ...]
                            // sorted by cell index — mirrors PatternKey::to_password_bytes()
                            val cells = firstPattern.entries
                                .sortedBy { it.key }
                                .flatMap { listOf(it.key.toByte(), it.value.toByte()) }
                                .toByteArray()
                            viewModel.createFromPattern(cells, nickname.trim())
                        }
                    },
                )

                RegStep.LOADING -> LoadingStep()

                RegStep.DONE -> DoneStep()
            }
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun DrawStep(
    title: String,
    subtitle: String,
    cells: Map<Int, Int>,
    onCellTap: (Int) -> Unit,
    shakeError: Boolean,
    onClear: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
    nextLabel: String,
    hintText: String,
    hintIsError: Boolean = false,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        PatternBoardView(
            selectedCells = cells,
            onCellTap = onCellTap,
            shakeError = shakeError,
            showCounter = true,
        )

        // Hint row
        AnimatedVisibility(visible = hintText.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (hintIsError)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer,
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
                        tint = if (hintIsError)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hintIsError)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.button_clear))
            }
            Button(
                onClick = onNext,
                enabled = nextEnabled,
                modifier = Modifier.weight(2f),
            ) {
                Text(nextLabel)
            }
        }

        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) {
                Text(secondaryActionLabel)
            }
        }
    }
}

@Composable
private fun NicknameStep(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onRegister: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_name_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = stringResource(R.string.profile_name_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = nickname,
            onValueChange = { if (it.length <= 32) onNicknameChange(it) },
            label = { Text(stringResource(R.string.profile_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onRegister() }),
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                } else {
                    // V-35: character counter
                    Text(
                        text = "${nickname.length}/32",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onRegister,
            enabled = nickname.trim().isNotEmpty() && nickname.trim().length <= 64 && !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.profile_create_button), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun LoadingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.profile_creating_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.profile_creating_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun DoneStep() {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(500),
        label = "doneAlpha"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.profile_created_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}
