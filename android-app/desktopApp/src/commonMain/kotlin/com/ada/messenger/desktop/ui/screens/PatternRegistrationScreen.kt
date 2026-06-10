package com.ada.messenger.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ada.messenger.desktop.ui.components.PATTERN_CUBES
import com.ada.messenger.desktop.ui.components.PatternBoardView
import com.ada.messenger.desktop.ui.components.cycleCell
import kotlinx.coroutines.delay

private enum class RegStep {
    DRAW,
    CONFIRM,
    NICKNAME,
    PIN,
    LOADING,
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PatternRegistrationScreen(
    patternLoading: Boolean,
    patternError: String?,
    onRegister: (ByteArray, String, String?) -> Unit,
    onClearError: () -> Unit,
    onLinkFromPhone: (() -> Unit)? = null,
) {
    var step by remember { mutableStateOf(RegStep.DRAW) }
    var firstPattern by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var confirmPattern by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var nickname by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var shakeError by remember { mutableStateOf(false) }

    val errorText = localError ?: patternError

    LaunchedEffect(patternLoading) {
        if (patternLoading) {
            step = RegStep.LOADING
        }
    }

    LaunchedEffect(patternError) {
        if (patternError != null && step == RegStep.LOADING) {
            step = RegStep.PIN
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
                .widthIn(max = 620.dp)
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
                Text(
                    text = "ADA Messenger",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        slideInHorizontally { it / 3 } + fadeIn() togetherWith
                            slideOutHorizontally { -it / 3 } + fadeOut()
                    },
                    label = "desktopRegistrationStep",
                ) { currentStep ->
                    when (currentStep) {
                        RegStep.DRAW -> RegistrationDrawStep(
                            title = "Создание профиля",
                            subtitle = "Выберите 16 ячеек. Повторное нажатие меняет цвет.",
                            cells = firstPattern,
                            shakeError = shakeError,
                            hintText = errorText,
                            hintIsError = !errorText.isNullOrBlank(),
                            onCellTap = { idx ->
                                val next = cycleCell(firstPattern, idx)
                                if (next == firstPattern && firstPattern.size >= PATTERN_CUBES && firstPattern[idx] == null) {
                                    localError = "Можно выбрать только 16 кубиков"
                                    shakeError = true
                                } else {
                                    firstPattern = next
                                    localError = null
                                    onClearError()
                                }
                            },
                            onPrimary = {
                                if (firstPattern.size == PATTERN_CUBES) {
                                    confirmPattern = emptyMap()
                                    localError = null
                                    onClearError()
                                    step = RegStep.CONFIRM
                                } else {
                                    localError = "Нужно выбрать ровно 16 кубиков"
                                    shakeError = true
                                }
                            },
                            onSecondary = {
                                firstPattern = emptyMap()
                                localError = null
                                onClearError()
                            },
                            primaryEnabled = firstPattern.size == PATTERN_CUBES,
                            primaryLabel = "Далее",
                            secondaryLabel = "Очистить",
                        )

                        RegStep.CONFIRM -> RegistrationDrawStep(
                            title = "Подтверждение",
                            subtitle = "Повторите тот же рисунок.",
                            cells = confirmPattern,
                            shakeError = shakeError,
                            hintText = errorText,
                            hintIsError = !errorText.isNullOrBlank(),
                            onCellTap = { idx ->
                                confirmPattern = cycleCell(confirmPattern, idx)
                                localError = null
                                onClearError()
                            },
                            onPrimary = {
                                if (confirmPattern != firstPattern) {
                                    confirmPattern = emptyMap()
                                    localError = "Рисунок не совпал. Повторите ещё раз."
                                    shakeError = true
                                } else {
                                    localError = null
                                    onClearError()
                                    step = RegStep.NICKNAME
                                }
                            },
                            onSecondary = {
                                confirmPattern = emptyMap()
                                localError = null
                                onClearError()
                                step = RegStep.DRAW
                            },
                            primaryEnabled = confirmPattern.size == PATTERN_CUBES,
                            primaryLabel = "Подтвердить",
                            secondaryLabel = "Назад",
                        )

                        RegStep.NICKNAME -> Column(
                            modifier = Modifier.widthIn(max = 420.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            StepHeader(
                                title = "Имя профиля",
                                subtitle = "Это имя увидят ваши контакты.",
                            )
                            OutlinedTextField(
                                value = nickname,
                                onValueChange = {
                                    nickname = it.take(64)
                                    localError = null
                                    onClearError()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Отображаемое имя") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    val trimmed = nickname.trim()
                                    if (trimmed.isBlank()) {
                                        localError = "Введите отображаемое имя"
                                    } else {
                                        step = RegStep.PIN
                                    }
                                }),
                            )
                            if (!errorText.isNullOrBlank()) {
                                SupportStrip(text = errorText, isError = true)
                            }
                            StepActions(
                                primaryLabel = if (patternLoading) "Создаём..." else "Далее",
                                primaryEnabled = nickname.trim().isNotBlank() && !patternLoading,
                                onPrimary = {
                                    val trimmed = nickname.trim()
                                    if (trimmed.isBlank()) {
                                        localError = "Введите отображаемое имя"
                                    } else {
                                        localError = null
                                        onClearError()
                                        step = RegStep.PIN
                                    }
                                },
                                secondaryLabel = "Назад",
                                onSecondary = {
                                    localError = null
                                    onClearError()
                                    step = RegStep.CONFIRM
                                },
                            )
                        }

                        RegStep.PIN -> Column(
                            modifier = Modifier.widthIn(max = 420.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            StepHeader(
                                title = "PIN для быстрого входа",
                                subtitle = "Необязательно. Рисунок останется резервным способом входа.",
                            )
                            OutlinedTextField(
                                value = pin,
                                onValueChange = {
                                    if (it.length <= 4 && it.all(Char::isDigit)) {
                                        pin = it
                                        localError = null
                                        onClearError()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("PIN-код") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Next,
                                ),
                            )
                            OutlinedTextField(
                                value = confirmPin,
                                onValueChange = {
                                    if (it.length <= 4 && it.all(Char::isDigit)) {
                                        confirmPin = it
                                        localError = null
                                        onClearError()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Повторите PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = {
                                    val trimmed = nickname.trim()
                                    when {
                                        pin.length != 4 -> localError = "PIN должен быть ровно 4 цифры"
                                        confirmPin != pin -> localError = "PIN-коды не совпадают"
                                        trimmed.isBlank() -> localError = "Введите отображаемое имя"
                                        else -> {
                                            localError = null
                                            onClearError()
                                            step = RegStep.LOADING
                                            onRegister(firstPattern.toPatternBytes(), trimmed, pin)
                                        }
                                    }
                                }),
                            )
                            if (!errorText.isNullOrBlank()) {
                                SupportStrip(text = errorText, isError = true)
                            }
                            StepActions(
                                primaryLabel = if (patternLoading) "Создаём..." else "Сохранить и создать",
                                primaryEnabled = !patternLoading,
                                onPrimary = {
                                    val trimmed = nickname.trim()
                                    when {
                                        pin.length != 4 -> localError = "PIN должен быть ровно 4 цифры"
                                        confirmPin != pin -> localError = "PIN-коды не совпадают"
                                        trimmed.isBlank() -> localError = "Введите отображаемое имя"
                                        else -> {
                                            localError = null
                                            onClearError()
                                            step = RegStep.LOADING
                                            onRegister(firstPattern.toPatternBytes(), trimmed, pin)
                                        }
                                    }
                                },
                                secondaryLabel = "Без PIN",
                                onSecondary = {
                                    val trimmed = nickname.trim()
                                    if (trimmed.isBlank()) {
                                        localError = "Введите отображаемое имя"
                                    } else {
                                        localError = null
                                        onClearError()
                                        step = RegStep.LOADING
                                        onRegister(firstPattern.toPatternBytes(), trimmed, null)
                                    }
                                },
                            )
                            TextButton(onClick = {
                                localError = null
                                onClearError()
                                step = RegStep.NICKNAME
                            }) {
                                Text("Назад к имени")
                            }
                        }

                        RegStep.LOADING -> LoadingStep()
                    }
                }

                if (onLinkFromPhone != null && step == RegStep.DRAW) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onLinkFromPhone) {
                        Text("Синхронизировать с телефоном по Wi-Fi")
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistrationDrawStep(
    title: String,
    subtitle: String,
    cells: Map<Int, Int>,
    shakeError: Boolean,
    hintText: String?,
    hintIsError: Boolean,
    onCellTap: (Int) -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    primaryEnabled: Boolean,
    primaryLabel: String,
    secondaryLabel: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepHeader(title = title, subtitle = subtitle)
        PatternBoardView(
            selectedCells = cells,
            onCellTap = onCellTap,
            shakeError = shakeError,
            showCounter = true,
            cellSize = 30.dp,
        )
        if (!hintText.isNullOrBlank()) {
            SupportStrip(text = hintText, isError = hintIsError)
        }
        StepActions(
            primaryLabel = primaryLabel,
            primaryEnabled = primaryEnabled,
            onPrimary = onPrimary,
            secondaryLabel = secondaryLabel,
            onSecondary = onSecondary,
        )
    }
}

@Composable
private fun LoadingStep() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = "Создаём профиль...",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Это может занять несколько секунд.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepActions(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
        ) {
            Text(primaryLabel)
        }
        OutlinedButton(
            onClick = onSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
        ) {
            Text(secondaryLabel)
        }
    }
}
