package com.ada.messenger.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Checks whether the app is excluded from battery optimizations and provides
 * an [Intent] to open the system dialog.
 *
 * This mirrors what Telegram does: shown once after first login, non-blocking.
 * The user can decline and the app still works (foreground service + wakelock
 * give us partial protection on most devices even without the exemption).
 */
object BatteryOptimizationHelper {

    private const val PREF_ASKED = "battery_opt_asked"
    private const val PREFS_NAME = "ada_prefs"

    /** True if the system has already granted an optimization exemption. */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Returns true if we should show the dialog now:
     * - Not yet exempted
     * - Not yet asked on this device
     */
    fun shouldAsk(ctx: Context): Boolean {
        if (isIgnoringBatteryOptimizations(ctx)) return false
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(PREF_ASKED, false)
    }

    /** Mark "already asked" so we don't show again. */
    fun markAsked(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ASKED, true)
            .apply()
    }

    /**
     * Returns an Intent that opens the system "Disable battery optimization" dialog.
     * On Android < M returns null (not needed).
     */
    fun buildRequestIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        )
    }
}
