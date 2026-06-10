package com.ada.messenger.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ada.messenger.desktop.ui.components.PATTERN_CUBES
import com.ada.messenger.desktop.ui.components.PatternBoardView
import com.ada.messenger.desktop.ui.components.cycleCell
import kotlinx.coroutines.delay

@Composable
fun PatternLoginScreen(
    storedDisplayName: String?,
    canUsePin: Boolean,
    patternLoading: Boolean,
    patternError: String?,
    onLogin: (ByteArray) -> Unit,
    onUsePin: () -> Unit,
    onClearError: () -> Unit,
    onNewUser: () -> Unit,
) {
    var cells by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var shakeError by remember { mutableStateOf(false) }
    var showNewUserConfirm by remember { mutableStateOf(false) }

    val errorText = localError ?: patternError

    LaunchedEffect(patternError) {
        if (patternError != null) {
            cells = emptyMap()
            shakeError = true
        }
    }

    LaunchedEffect(shakeError) {
        if (shakeError) {
            delay(450)
            shakeError = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )

                Text(
                    text = "Вход по рисунку",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (storedDisplayName.isNullOrBlank()) {
                        "Повторите рисунок, чтобы открыть локальный профиль."
                    } else {
                        "Профиль $storedDisplayName уже сохранён на этом устройстве."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                PatternBoardView(
                    selectedCells = cells,
                    onCellTap = { idx ->
                        cells = cycleCell(cells, idx)
                        localError = null
                        onClearError()
                    },
                    shakeError = shakeError,
                    showCounter = true,
                    cellSize = 30.dp,
                )

                if (!errorText.isNullOrBlank()) {
                    SupportStrip(text = errorText, isError = true)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            cells = emptyMap()
                            localError = null
                            onClearError()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        enabled = !patternLoading,
                    ) {
                        Text("Очистить")
                    }

                    Button(
                        onClick = {
                            if (cells.size != PATTERN_CUBES) {
                                localError = "Нужно выбрать ровно 16 кубиков"
                                shakeError = true
                            } else {
                                localError = null
                                onClearError()
                                onLogin(cells.toPatternBytes())
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        enabled = !patternLoading,
                    ) {
                        if (patternLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Войти")
                        }
                    }
                }

                if (canUsePin) {
                    OutlinedButton(
                        onClick = onUsePin,
                        enabled = !patternLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    ) {
                        Text("Использовать PIN")
                    }
                }

                TextButton(
                    onClick = { showNewUserConfirm = true },
                    enabled = !patternLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Новый пользователь", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showNewUserConfirm) {
        AlertDialog(
            onDismissRequest = { showNewUserConfirm = false },
            title = { Text("Создать нового пользователя?") },
            text = { Text("Текущий профиль будет удалён с этого устройства. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = { showNewUserConfirm = false; onNewUser() }) {
                    Text("Создать", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewUserConfirm = false }) { Text("Отмена") }
            },
        )
    }
}