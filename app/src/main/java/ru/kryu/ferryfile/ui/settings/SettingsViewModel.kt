package ru.kryu.ferryfile.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kryu.ferryfile.data.PreferencesRepository
import ru.kryu.ferryfile.server.auth.PasswordHasher
import javax.inject.Inject

data class SettingsUiState(
    val port: Int = 8080,
    val hasPassword: Boolean = false,
    val darkTheme: Boolean = true,
    val safUris: List<String> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val hasher: PasswordHasher,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = SettingsUiState(
            port = prefs.port,
            hasPassword = prefs.passwordHash.isNotEmpty(),
            darkTheme = prefs.darkTheme,
            safUris = prefs.safUris
        )
    }

    fun setPort(port: Int) {
        if (port !in 1024..65535) return
        prefs.port = port
        _uiState.update { it.copy(port = port) }
    }

    fun setPassword(newPassword: String) {
        if (newPassword.isBlank()) return
        viewModelScope.launch {
            val hash = withContext(Dispatchers.Default) { hasher.hash(newPassword) }
            prefs.passwordHash = hash
            _uiState.update { it.copy(hasPassword = true) }
        }
    }

    fun clearPassword() {
        prefs.passwordHash = ""
        _uiState.update { it.copy(hasPassword = false) }
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.darkTheme = enabled
        _uiState.update { it.copy(darkTheme = enabled) }
    }

    fun addSafUri(uri: Uri) {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess
        if (!granted) return
        val updated = prefs.safUris + uri.toString()
        prefs.safUris = updated
        _uiState.update { it.copy(safUris = updated) }
    }

    fun removeSafUri(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val updated = prefs.safUris.filter { it != uri }
        prefs.safUris = updated
        _uiState.update { it.copy(safUris = updated) }
    }
}
