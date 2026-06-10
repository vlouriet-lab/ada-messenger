package com.ada.messenger.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ada.messenger.desktop.ui.components.DesktopQrCode

/**
 * Shown while the desktop Wi-Fi link server is running.
 * Displays a QR code that the Android app should scan to send the snapshot.
 */
@Composable
fun WifiLinkWaitingScreen(
    linkUrl: String?,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Синхронизация с телефоном",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Откройте ADA на телефоне → Профиль → Синхронизация с ПК.\n" +
                "Убедитесь, что оба устройства в одной Wi-Fi сети, затем отсканируйте QR.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        Spacer(Modifier.height(32.dp))

        if (linkUrl != null) {
            DesktopQrCode(content = linkUrl, size = 280.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = linkUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        } else {
            CircularProgressIndicator()
        }

        Spacer(Modifier.height(32.dp))

        OutlinedButton(onClick = onCancel) {
            Text("Отмена")
        }
    }
}
