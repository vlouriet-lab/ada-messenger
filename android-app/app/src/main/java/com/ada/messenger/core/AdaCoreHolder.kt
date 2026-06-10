package com.ada.messenger.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-level singleton that holds the single [AdaCore] instance.
 *
 * ## Why this exists (D1 fix)
 * Both [AdaCoreViewModel] and [AdaForegroundService] used to create their own
 * [AdaCore] objects, resulting in two separate libp2p swarms, two separate
 * Tokio runtimes, and duplicate SQLite connections — causing corruption,
 * missing messages, and higher resource usage.
 *
 * With this holder:
 * - The ViewModel is always the *owner* — it creates the core, stores it here,
 *   and nulls it out (calling [AdaCore.close]) in [AdaCoreViewModel.onCleared].
 * - The Service reads from here; if the instance is already set it reuses it.
 *   On a START_STICKY restart (process was killed, no ViewModel yet) the Service
 *   creates a fresh core and stores it here so the ViewModel can adopt it on the
 *   next Activity open.
 */
object AdaCoreHolder {
    private var _instance: AdaCore? = null
    var instance: AdaCore?
        get() = _instance
        set(value) {
            _instance = value
            if (value != null) {
                startPolling()
            } else {
                stopPolling()
            }
        }

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 50)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var pollJob: Job? = null
    private val pollScope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = pollScope.launch {
            while (isActive) {
                val core = _instance
                if (core != null) {
                    val eventJson = core.pollEventJson(100)
                    if (eventJson != null) {
                        _events.emit(eventJson)
                    } else {
                        delay(50)
                    }
                } else {
                    delay(500)
                }
            }
        }
    }

    @Synchronized
    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Deprecated flag for single-consumer risk workaround.
     * No longer needed since events are broadcasted via SharedFlow.
     */
    @Volatile var isViewModelActive = false
}
