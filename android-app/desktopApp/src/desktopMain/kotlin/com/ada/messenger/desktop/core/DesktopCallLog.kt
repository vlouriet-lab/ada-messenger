package com.ada.messenger.desktop.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal object DesktopCallLog {
    private val lock = Any()
    private var logFile: Path? = null

    fun configure(coreDataDir: Path) {
        synchronized(lock) {
            runCatching { Files.createDirectories(coreDataDir) }
            val resolved = coreDataDir.resolve("ada-desktop-ui.log")
            if (logFile == resolved) {
                return
            }
            logFile = resolved
            appendLocked("INFO", "desktop call log initialized")
        }
    }

    fun info(message: String) {
        append("INFO", message)
    }

    fun debug(message: String) {
        append("DEBUG", message)
    }

    fun warn(message: String, error: Throwable? = null) {
        val body = if (error == null) {
            message
        } else {
            buildString {
                append(message)
                append(" :: ")
                append(error.javaClass.simpleName)
                error.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
                append('\n')
                append(error.stackTraceToString())
            }
        }
        append("WARN", body)
    }

    private fun append(level: String, message: String) {
        synchronized(lock) {
            appendLocked(level, message)
        }
    }

    private fun appendLocked(level: String, message: String) {
        val path = logFile ?: return
        val line = buildString {
            append(Instant.now())
            append(' ')
            append('[')
            append(level)
            append("] ")
            append(message.trimEnd())
            append('\n')
        }
        runCatching {
            Files.writeString(
                path,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        }
    }
}