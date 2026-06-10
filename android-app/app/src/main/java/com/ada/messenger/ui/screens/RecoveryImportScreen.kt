package com.ada.messenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ada.messenger.R
import com.ada.messenger.core.AdaCoreViewModel
import kotlinx.coroutines.launch

private enum class RecoveryImportMode {
    FILE,
    CODE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryImportScreen(
    viewModel: AdaCoreViewModel,
    onBack: () -> Unit,
    onImported: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var importMode by remember { mutableStateOf(RecoveryImportMode.FILE) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val unknownFileLabel = stringResource(R.string.recovery_import_unknown_file)
    val importFailedLabel = stringResource(R.string.recovery_import_failed)
    val fileNoteLabel = stringResource(R.string.recovery_import_file_note)
    val codeNoteLabel = stringResource(R.string.recovery_import_code_note)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            error = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recovery_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.button_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.recovery_import_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = if (importMode == RecoveryImportMode.FILE) fileNoteLabel else codeNoteLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            TabRow(selectedTabIndex = importMode.ordinal) {
                Tab(
                    selected = importMode == RecoveryImportMode.FILE,
                    onClick = {
                        importMode = RecoveryImportMode.FILE
                        error = null
                    },
                    text = { Text(stringResource(R.string.recovery_import_tab_file)) },
                )
                Tab(
                    selected = importMode == RecoveryImportMode.CODE,
                    onClick = {
                        importMode = RecoveryImportMode.CODE
                        error = null
                    },
                    text = { Text(stringResource(R.string.recovery_import_tab_code)) },
                )
            }

            if (importMode == RecoveryImportMode.FILE) {
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recovery_import_pick_file))
                }

                Text(
                    text = if (selectedUri != null) {
                        stringResource(
                            R.string.recovery_import_selected_file,
                            selectedUri?.lastPathSegment ?: unknownFileLabel,
                        )
                    } else {
                        stringResource(R.string.recovery_import_no_file)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.recovery_password_label)) },
                    supportingText = {
                        Text(stringResource(R.string.recovery_password_hint))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                    },
                )
            } else {
                OutlinedTextField(
                    value = recoveryCode,
                    onValueChange = {
                        recoveryCode = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.recovery_import_code_label)) },
                    supportingText = {
                        Text(stringResource(R.string.recovery_import_code_hint))
                    },
                    minLines = 3,
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        if (it.length <= 64) {
                            displayName = it
                            error = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.recovery_import_display_name_label)) },
                    supportingText = {
                        Text(stringResource(R.string.recovery_import_display_name_hint))
                    },
                    singleLine = true,
                )
            }

            if (error != null) {
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            if (importMode == RecoveryImportMode.FILE) {
                                val uri = selectedUri ?: return@launch
                                viewModel.importRecoveryBundle(uri, password)
                            } else {
                                viewModel.importRecoveryCode(recoveryCode, displayName)
                            }
                            onImported()
                        } catch (e: Exception) {
                            error = e.message ?: importFailedLabel
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && when (importMode) {
                    RecoveryImportMode.FILE -> selectedUri != null && password.length >= 8
                    RecoveryImportMode.CODE -> recoveryCode.isNotBlank() && displayName.trim().isNotEmpty()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = if (importMode == RecoveryImportMode.FILE) {
                            stringResource(R.string.recovery_import_confirm_button)
                        } else {
                            stringResource(R.string.recovery_import_code_confirm_button)
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    }
}