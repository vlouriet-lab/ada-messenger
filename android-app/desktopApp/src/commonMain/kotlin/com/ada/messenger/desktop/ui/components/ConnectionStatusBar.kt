package com.ada.messenger.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ada.messenger.desktop.model.DesktopBridgeStatus

private enum class ConnectionLevel { Good, Degraded, Slow, Failed }

private fun routeToLevel(status: DesktopBridgeStatus?): ConnectionLevel {
    if (status == null) return ConnectionLevel.Failed
    return when (status.lastRoute) {
        "iroh_live" -> ConnectionLevel.Good
        "local_mesh",
        "bridge_websocket_tls",
        "bridge_domain_front",
        "bridge_meek",
        "bridge_obfs4" -> ConnectionLevel.Degraded
        "mailbox_bridge",
        "offline_queue" -> ConnectionLevel.Slow
        null -> when {
            status.irohReady -> ConnectionLevel.Good
            status.hasWorking -> ConnectionLevel.Degraded
            else -> ConnectionLevel.Failed
        }
        else -> ConnectionLevel.Failed
    }
}

private fun routeLabel(status: DesktopBridgeStatus?): String {
    val route = status?.lastRoute ?: return when {
        status?.irohReady == true -> "Прямое соединение"
        status?.hasWorking == true -> "Соединение активно"
        else -> "Подключение..."
    }
    return when (route) {
        "iroh_live" -> "Прямое соединение"
        "bridge_websocket_tls" -> "Bridge · WebSocket TLS"
        "bridge_domain_front" -> "Bridge · Domain Front"
        "bridge_meek" -> "Bridge · Meek"
        "bridge_obfs4" -> "Bridge · obfs4"
        "mailbox_bridge" -> "Mailbox (асинхронно)"
        "local_mesh" -> "Локальная сеть"
        "offline_queue" -> "Офлайн — очередь отправки"
        "failed" -> "Нет маршрута"
        else -> route
    }
}

@Composable
fun ConnectionStatusBar(
    bridgeStatus: DesktopBridgeStatus?,
    modifier: Modifier = Modifier,
) {
    val level = routeToLevel(bridgeStatus)

    val bgColor by animateColorAsState(
        targetValue = when (level) {
            ConnectionLevel.Good -> Color(0xFF1B5E20)
            ConnectionLevel.Degraded -> Color(0xFF4E4100)
            ConnectionLevel.Slow -> Color(0xFF4E2600)
            ConnectionLevel.Failed -> Color(0xFF4E0000)
        },
        animationSpec = tween(durationMillis = 400),
        label = "statusBg",
    )

    val dotColor by animateColorAsState(
        targetValue = when (level) {
            ConnectionLevel.Good -> Color(0xFF66BB6A)
            ConnectionLevel.Degraded -> Color(0xFFFFD54F)
            ConnectionLevel.Slow -> Color(0xFFFFB74D)
            ConnectionLevel.Failed -> Color(0xFFEF5350)
        },
        animationSpec = tween(durationMillis = 400),
        label = "statusDot",
    )

    val textColor = when (level) {
        ConnectionLevel.Good -> Color(0xFFA5D6A7)
        ConnectionLevel.Degraded -> Color(0xFFFFF9C4)
        ConnectionLevel.Slow -> Color(0xFFFFE0B2)
        ConnectionLevel.Failed -> Color(0xFFFFCDD2)
    }

    // GREEN = thin 3dp decorative stripe (no text); any other = compact 22dp info bar
    val isGood = level == ConnectionLevel.Good
    val barHeight = if (isGood) 3.dp else 22.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(bgColor)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!isGood) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = routeLabel(bridgeStatus),
                color = textColor,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
