package com.ada.messenger.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ada.messenger.desktop.ui.components.PatternBoardView
import com.ada.messenger.desktop.ui.components.cycleCell
import kotlinx.coroutines.delay

private enum class SnapshotRegStep { DRAW, CONFIRM, LOADING }

/**
 * Shown after the phone snapshot has been received on the desktop.
 *
 * The user draws a pattern that will protect the local database.
 * Unlike [PatternRegistrationScreen] we skip the nickname step (taken from
 * the snapshot) and the PIN step (can be set later in settings).
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SnapshotPatternScreen(
    patternLoading: Boolean,
    patternError: String?,
    onSetPattern: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
) {
    var step by remember { mutableStateOf(SnapshotRegStep.DRAW) }
    var firstPattern by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var confirmPattern by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var shakeError by remember { mutableStateOf(false) }

    val errorText = localError ?: patternError

    LaunchedEffect(patternLoading) {
        if (!patternLoading) {
            step = SnapshotRegStep.DRAW
            firstPattern = emptyMap()
            confirmPattern = emptyMap()
        }
    }

    LaunchedEffect(shakeError) {
        if (shakeError) {
            delay(500)
            shakeError = false
        }
    }

    fun buildCells(pattern: Map<Int, Int>): ByteArray {
        val buf = ByteArray(32)
        pattern.entries.forEachIndexed { i, (idx, color) ->
            buf[i * 2] = idx.toByte()
            buf[i * 2 + 1] = color.toByte()
        }
        return buf
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Защита десктоп-профиля",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Аккаунт получен с телефона. Задайте графический ключ для шифрования\n" +
                "локальной базы данных на этом устройстве.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        Spacer(Modifier.height(24.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
            },
        ) { currentStep ->
            Column(
                modifier = Modifier.widthIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (currentStep) {
                    SnapshotRegStep.DRAW -> {
                        Text(
                            "Нарисуйте ключ",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        PatternBoardView(
                            selectedCells = firstPattern,
                            onCellTap = { idx ->
                                firstPattern = cycleCell(firstPattern, idx)
                                localError = null
                                onClearError()
                            },
                            shakeError = shakeError,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (firstPattern.size < 4) {
                                    localError = "Нужно выбрать не менее 4 кубиков"
                                    shakeError = true
                                } else {
                                    localError = null
                                    onClearError()
                                    step = SnapshotRegStep.CONFIRM
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Далее")
                        }
                    }

                    SnapshotRegStep.CONFIRM -> {
                        Text(
                            "Подтвердите ключ",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        PatternBoardView(
                            selectedCells = confirmPattern,
                            onCellTap = { idx ->
                                confirmPattern = cycleCell(confirmPattern, idx)
                                localError = null
                                onClearError()
                            },
                            shakeError = shakeError,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (confirmPattern != firstPattern) {
                                    confirmPattern = emptyMap()
                                    localError = "Ключи не совпадают. Повторите."
                                    shakeError = true
                                } else {
                                    localError = null
                                    onClearError()
                                    step = SnapshotRegStep.LOADING
                                    onSetPattern(buildCells(firstPattern))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Подтвердить")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                step = SnapshotRegStep.DRAW
                                confirmPattern = emptyMap()
                                localError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Назад")
                        }
                    }

                    SnapshotRegStep.LOADING -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Импорт аккаунта…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (errorText != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (step != SnapshotRegStep.LOADING) {
            OutlinedButton(onClick = {
                onClearError()
                onCancel()
            }) {
                Text("Отмена")
            }
        }
    }
}
