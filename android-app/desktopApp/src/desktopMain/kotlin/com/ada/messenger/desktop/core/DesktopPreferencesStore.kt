package com.ada.messenger.desktop.core

import com.ada.messenger.desktop.ui.theme.ThemeMode
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

data class DesktopPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val customManifestUrl: String = "",
    val customManifestPublicKey: String = "",
    val contactAliases: Map<String, String> = emptyMap(),
)

class DesktopPreferencesStore private constructor(private val prefsPath: Path) {
    init {
        Files.createDirectories(prefsPath.parent)
    }

    fun load(): DesktopPreferences {
        if (!Files.isRegularFile(prefsPath)) return DesktopPreferences()
        return runCatching {
            val json = JSONObject(Files.readString(prefsPath))
            DesktopPreferences(
                themeMode = parseThemeMode(json.optString("themeMode", ThemeMode.SYSTEM.name)),
                customManifestUrl = json.optString("customManifestUrl", "").trim(),
                customManifestPublicKey = json.optString("customManifestPublicKey", "").trim(),
                contactAliases = json.optJSONObject("contactAliases")
                    ?.let(::parseContactAliases)
                    .orEmpty(),
            )
        }.getOrDefault(DesktopPreferences())
    }

    fun saveThemeMode(themeMode: ThemeMode) {
        val current = load()
        save(
            current.copy(
                themeMode = themeMode,
            ),
        )
    }

    fun saveCustomBridgeBootstrap(manifestUrl: String, trustedPublicKeyHex: String) {
        val current = load()
        save(
            current.copy(
                customManifestUrl = manifestUrl.trim(),
                customManifestPublicKey = trustedPublicKeyHex.trim(),
            ),
        )
    }

    fun saveContactAlias(peerId: String, displayName: String) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return

        val current = load()
        val aliases = current.contactAliases.toMutableMap()
        val normalizedName = displayName.trim()
        if (normalizedName.isBlank()) {
            aliases.remove(normalizedPeerId)
        } else {
            aliases[normalizedPeerId] = normalizedName
        }
        save(current.copy(contactAliases = aliases.toSortedMap()))
    }

    fun contactAliases(): Map<String, String> = load().contactAliases

    private fun save(preferences: DesktopPreferences) {
        val aliasesJson = JSONObject().apply {
            preferences.contactAliases
                .toSortedMap()
                .forEach { (peerId, displayName) ->
                    put(peerId, displayName)
                }
        }
        val json = JSONObject()
            .put("themeMode", preferences.themeMode.name)
            .put("customManifestUrl", preferences.customManifestUrl)
            .put("customManifestPublicKey", preferences.customManifestPublicKey)
            .put("contactAliases", aliasesJson)
        Files.createDirectories(prefsPath.parent)
        Files.writeString(prefsPath, json.toString(2))
    }

    private fun parseContactAliases(json: JSONObject): Map<String, String> =
        buildMap {
            json.keys().forEach { key ->
                val displayName = json.optString(key, "").trim()
                if (displayName.isNotBlank()) {
                    put(key, displayName)
                }
            }
        }

    private fun parseThemeMode(rawValue: String): ThemeMode = when (rawValue.trim().uppercase()) {
        ThemeMode.LIGHT.name -> ThemeMode.LIGHT
        ThemeMode.DARK.name -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

    companion object {
        fun default(): DesktopPreferencesStore =
            DesktopPreferencesStore(DesktopIdentityStore.defaultBaseDir().resolve("desktop-settings.json"))
    }
}