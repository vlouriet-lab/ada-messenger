package com.ada.messenger.ui.components

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ada.messenger.R
import com.ada.messenger.service.BatteryOptimizationHelper

/**
 * One-time dialog that asks the user to exempt ADA from battery optimizations.
 *
 * Mirrors Telegram's approach:
 *  - Appear once after first successful login.
 *  - "Allow" → opens system dialog → Android handles the grant.
 *  - "Later" → dismissed, never shown again (same as "Allow" press for flag).
 *
 * @param onDismiss called when the dialog is closed (either action).
 */
@Composable
fun BatteryOptimizationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            BatteryOptimizationHelper.markAsked(context)
            onDismiss()
        },
        title = { Text(stringResource(R.string.battery_dialog_title)) },
        text = { Text(stringResource(R.string.battery_dialog_text)) },
        confirmButton = {
            TextButton(onClick = {
                BatteryOptimizationHelper.markAsked(context)
                BatteryOptimizationHelper.buildRequestIntent(context)?.let { intent ->
                    context.startActivity(intent)
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.battery_dialog_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                BatteryOptimizationHelper.markAsked(context)
                onDismiss()
            }) {
                Text(stringResource(R.string.battery_dialog_later))
            }
        },
    )
}
