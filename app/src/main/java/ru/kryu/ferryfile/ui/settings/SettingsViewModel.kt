package ru.kryu.ferryfile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject

data class SettingsUiState(
    val port: Int = 8080,
    val darkTheme: Boolean = true,
    val sharedFolders: List<SharedFolder> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.port, settings.darkTheme, settings.sharedFolders
    ) { port, dark, folders ->
        SettingsUiState(port = port.value, darkTheme = dark, sharedFolders = folders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setPort(port: Int) {
        val parsed = Port.parse(port) ?: return
        viewModelScope.launch { settings.setPort(parsed) }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { settings.setDarkTheme(enabled) }
    }

    fun addSafUri(uri: String) {
        viewModelScope.launch { settings.addSharedFolder(uri) }
    }

    fun removeSafUri(uri: String) {
        viewModelScope.launch { settings.removeSharedFolder(uri) }
    }
}
