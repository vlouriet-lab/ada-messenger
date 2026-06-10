package com.ada.messenger.desktop.core

import java.awt.image.BufferedImage

data class DesktopVideoCallUiState(
    val localFrame: BufferedImage? = null,
    val remoteFrame: BufferedImage? = null,
    val localVideoAvailable: Boolean = false,
    val remoteVideoAvailable: Boolean = false,
    val localAudioEnabled: Boolean = false,
    val localVideoEnabled: Boolean = false,
    val canSwitchCamera: Boolean = false,
    val canScreenShare: Boolean = false,
    val isScreenSharing: Boolean = false,
    val activeVideoSourceLabel: String? = null,
    val activeAudioOutputLabel: String? = null,
    val audioOutputCount: Int = 0,
    val updatedAtNanos: Long = 0L,
)