package ru.kryu.ferryfile.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.kryu.ferryfile.data.files.SafPermissionManager
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences,
    private val permissions: SafPermissionManager
) : SettingsRepository {

    private val _port = MutableStateFlow(readPort())
    override val port: StateFlow<Port> = _port.asStateFlow()

    private val _darkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK_THEME, true))
    override val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _useHttps = MutableStateFlow(prefs.getBoolean(KEY_USE_HTTPS, false))
    override val useHttps: StateFlow<Boolean> = _useHttps.asStateFlow()

    private val _sharedFolders = MutableStateFlow(readUris().toFolders())
    override val sharedFolders: StateFlow<List<SharedFolder>> = _sharedFolders.asStateFlow()

    override suspend fun setPort(port: Port) {
        prefs.edit().putInt(KEY_PORT, port.value).apply()
        _port.value = port
    }

    override suspend fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        _darkTheme.value = enabled
    }

    override suspend fun setUseHttps(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_HTTPS, enabled).apply()
        _useHttps.value = enabled
    }

    override suspend fun addSharedFolder(uri: String): Boolean {
        val stored = readUris()
        if (uri in stored) return true
        if (!permissions.takePersistable(uri)) return false
        writeUris(stored + uri)
        return true
    }

    override suspend fun removeSharedFolder(uri: String) {
        permissions.release(uri)
        writeUris(readUris().filterNot { it == uri })
    }

    private fun readPort(): Port =
        Port.parse(prefs.getInt(KEY_PORT, Port.DEFAULT.value)) ?: Port.DEFAULT

    private fun readUris(): List<String> = runCatching {
        Json.decodeFromString<List<String>>(prefs.getString(KEY_SAF_URIS, EMPTY_JSON_ARRAY) ?: EMPTY_JSON_ARRAY)
    }.getOrDefault(emptyList())

    private fun writeUris(uris: List<String>) {
        prefs.edit().putString(KEY_SAF_URIS, Json.encodeToString(uris)).apply()
        _sharedFolders.value = uris.toFolders()
    }

    private fun List<String>.toFolders(): List<SharedFolder> =
        map { SharedFolder(it, permissions.displayName(it)) }

    private companion object {
        const val KEY_PORT = "server_port"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_USE_HTTPS = "use_https"
        const val KEY_SAF_URIS = "saf_uris"
        const val EMPTY_JSON_ARRAY = "[]"
    }
}
