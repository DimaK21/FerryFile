package ru.kryu.ferryfile.data

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepository @Inject constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_PORT = "server_port"
        private const val KEY_SAF_URIS = "saf_uris"
        private const val KEY_DARK_THEME = "dark_theme"
        const val DEFAULT_PORT = 8080
    }

    var passwordHash: String
        get() = prefs.getString(KEY_PASSWORD_HASH, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PASSWORD_HASH, value).apply() }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) { prefs.edit().putInt(KEY_PORT, value).apply() }

    var safUris: List<String>
        get() = runCatching {
            Json.decodeFromString<List<String>>(prefs.getString(KEY_SAF_URIS, "[]") ?: "[]")
        }.getOrDefault(emptyList())
        set(value) { prefs.edit().putString(KEY_SAF_URIS, Json.encodeToString(value)).apply() }

    var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, true)
        set(value) { prefs.edit().putBoolean(KEY_DARK_THEME, value).apply() }
}
