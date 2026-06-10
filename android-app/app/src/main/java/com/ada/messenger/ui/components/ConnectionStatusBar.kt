package com.ada.messenger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ada.messenger.R
import com.ada.messenger.core.AdaCoreViewModel.ConnectionLevel
import com.ada.messenger.core.TransportOutcomeSummary

// Palette — chosen for sufficient contrast on both dark and light themes.
private val GreenBg = Color(0xFF1B5E20)
private val GreenDot = Color(0xFF66BB6A)
private val YellowBg = Color(0xFF4E4100)
private val YellowDot = Color(0xFFFFD54F)
private val OrangeBg = Color(0xFF4E2600)
private val OrangeDot = Color(0xFFFFB74D)
private val RedBg = Color(0xFF4E0000)
private val RedDot = Color(0xFFEF5350)

/**
 * Thin colour-coded bar showing the current network connection health.
 *
 * - **GREEN** — connected, messages delivered instantly.
 * - **YELLOW** — connected with possible delays.
 * - **ORANGE** — limited connectivity, heavy features (calls) unavailable.
 * - **RED** — no connection at all.
 *
 * The bar animates smoothly between states. When the connection is GREEN the
 * bar is as unobtrusive as possible (single thin line).
 */
internal fun connectionRouteLabelRes(route: String?): Int? = when (route) {
    "iroh_live" -> R.string.conn_route_iroh_live
    "bridge_websocket_tls",
    "bridge_domain_front",
    "bridge_meek",
    "bridge_obfs4" -> R.string.conn_route_bridge
    "local_mesh" -> R.string.conn_route_local_mesh
    "mailbox_bridge" -> R.string.conn_route_mailbox
    "offline_queue" -> R.string.conn_route_offline_queue
    "relay_only_deferred" -> R.string.conn_route_relay_only_deferred
    "failed" -> R.string.conn_route_failed
    else -> null
}

@Composable
fun ConnectionStatusBar(
    level: ConnectionLevel,
    modifier: Modifier = Modifier,
    lastOutcome: TransportOutcomeSummary? = null,
) {
    val bg by animateColorAsState(
        targetValue = when (level) {
            ConnectionLevel.GREEN  -> GreenBg
            ConnectionLevel.YELLOW -> YellowBg
            ConnectionLevel.ORANGE -> OrangeBg
            ConnectionLevel.RED    -> RedBg
        },
        animationSpec = tween(400),
        label = "conn_bg",
    )
    val dot by animateColorAsState(
        targetValue = when (level) {
            ConnectionLevel.GREEN  -> GreenDot
            ConnectionLevel.YELLOW -> YellowDot
            ConnectionLevel.ORANGE -> OrangeDot
            ConnectionLevel.RED    -> RedDot
        },
        animationSpec = tween(400),
        label = "conn_dot",
    )
    val textColor by animateColorAsState(
        targetValue = when (level) {
            ConnectionLevel.GREEN  -> Color(0xFFA5D6A7)
            ConnectionLevel.YELLOW -> Color(0xFFFFF9C4)
            ConnectionLevel.ORANGE -> Color(0xFFFFE0B2)
            ConnectionLevel.RED    -> Color(0xFFFFCDD2)
        },
        animationSpec = tween(400),
        label = "conn_text",
    )

    val baseLabel = when (level) {
        ConnectionLevel.GREEN  -> stringResource(R.string.conn_green)
        ConnectionLevel.YELLOW -> stringResource(R.string.conn_yellow)
        ConnectionLevel.ORANGE -> stringResource(R.string.conn_orange)
        ConnectionLevel.RED    -> stringResource(R.string.conn_red)
    }
    val routeLabel = connectionRouteLabelRes(lastOutcome?.route)?.let { stringResource(it) }
    val label = if (routeLabel != null) {
        stringResource(R.string.conn_with_route, baseLabel, routeLabel)
    } else {
        baseLabel
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot.copy(alpha = if (level == ConnectionLevel.GREEN) pulseAlpha else 1f))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
