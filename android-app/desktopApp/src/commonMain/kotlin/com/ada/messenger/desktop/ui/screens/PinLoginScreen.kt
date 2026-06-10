package com.ada.messenger.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun PinLoginScreen(
    storedDisplayName: String?,
    patternLoading: Boolean,
    patternError: String?,
    onLogin: (String) -> Unit,
    onUsePattern: () -> Unit,
    onClearError: () -> Unit,
    onNewUser: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showNewUserConfirm by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val appendDigit: (String) -> Unit = { digit ->
        if (pin.length < 4) {
            pin += digit
            localError = null
            onClearError()
            if (pin.length == 4) {
                onLogin(pin)
            }
        }
    }

    val removeDigit: () -> Unit = {
        if (pin.isNotEmpty()) {
            pin = pin.dropLast(1)
            localError = null
            onClearError()
        }
    }

    val clearPin: () -> Unit = {
        pin = ""
        localError = null
        onClearError()
    }

    LaunchedEffect(patternError) {
        if (!patternError.isNullOrBlank()) {
            localError = patternError
            pin = ""
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || patternLoading) {
                    return@onPreviewKeyEvent false
                }

                when (event.key) {
                    Key.Zero, Key.NumPad0 -> {
                        appendDigit("0")
                        true
                    }
                    Key.One, Key.NumPad1 -> {
                        appendDigit("1")
                        true
                    }
                    Key.Two, Key.NumPad2 -> {
                        appendDigit("2")
                        true
                    }
                    Key.Three, Key.NumPad3 -> {
                        appendDigit("3")
                        true
                    }
                    Key.Four, Key.NumPad4 -> {
                        appendDigit("4")
                        true
                    }
                    Key.Five, Key.NumPad5 -> {
                        appendDigit("5")
                        true
                    }
                    Key.Six, Key.NumPad6 -> {
                        appendDigit("6")
                        true
                    }
                    Key.Seven, Key.NumPad7 -> {
                        appendDigit("7")
                        true
                    }
                    Key.Eight, Key.NumPad8 -> {
                        appendDigit("8")
                        true
                    }
                    Key.Nine, Key.NumPad9 -> {
                        appendDigit("9")
                        true
                    }
                    Key.Backspace -> {
                        removeDigit()
                        true
                    }
                    Key.Delete -> {
                        clearPin()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 320.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                    text = "Вход по PIN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (storedDisplayName.isNullOrBlank()) {
                        "Быстрый вход включён для этого профиля."
                    } else {
                        "Профиль $storedDisplayName можно открыть PIN-кодом."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(4) { index ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    color = if (index < pin.length) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f)
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }

                if (!localError.isNullOrBlank()) {
                    SupportStrip(text = localError!!, isError = true)
                }

                PinPad(
                    modifier = Modifier.widthIn(max = 300.dp),
                    enabled = !patternLoading,
                    onDigit = appendDigit,
                    onBackspace = removeDigit,
                    onClear = clearPin,
                )

                if (patternLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }

                OutlinedButton(
                    onClick = onUsePattern,
                    enabled = !patternLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text("Использовать рисунок")
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

@Composable
private fun PinPad(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { digit ->
                    Button(
                        onClick = { onDigit(digit) },
                        enabled = enabled,
                        modifier = Modifier.size(60.dp),
                        shape = CircleShape,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            text = digit,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Сброс", modifier = Modifier.size(20.dp))
            }
            Button(
                onClick = { onDigit("0") },
                enabled = enabled,
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            OutlinedButton(
                onClick = onBackspace,
                enabled = enabled,
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("⌫", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}