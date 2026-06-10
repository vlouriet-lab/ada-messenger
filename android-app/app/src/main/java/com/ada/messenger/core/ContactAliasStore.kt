package com.ada.messenger.core

import android.content.Context

private const val CONTACT_ALIAS_PREFS = "contact_aliases"
private const val CONTACT_ALIAS_KEY_PREFIX = "alias_"

class ContactAliasStore(context: Context) {
    private val prefs = context.getSharedPreferences(CONTACT_ALIAS_PREFS, Context.MODE_PRIVATE)

    fun getAlias(peerIdB64: String): String? =
        prefs.getString(CONTACT_ALIAS_KEY_PREFIX + peerIdB64, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setAlias(peerIdB64: String, alias: String) {
        val trimmed = alias.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) {
                remove(CONTACT_ALIAS_KEY_PREFIX + peerIdB64)
            } else {
                putString(CONTACT_ALIAS_KEY_PREFIX + peerIdB64, trimmed)
            }
        }.apply()
    }
}