package ru.kryu.ferryfile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.usecase.AddSharedFolderUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveDarkThemeUseCase
import ru.kryu.ferryfile.domain.usecase.ObservePortUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveSharedFoldersUseCase
import ru.kryu.ferryfile.domain.usecase.RemoveSharedFolderUseCase
import ru.kryu.ferryfile.domain.usecase.SetDarkThemeUseCase
import ru.kryu.ferryfile.domain.usecase.SetPortUseCase
import javax.inject.Inject

data class SettingsUiState(
    val port: Int = 8080,
    val portError: Boolean = false,
    val darkTheme: Boolean = true,
    val sharedFolders: List<SharedFolder> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observePort: ObservePortUseCase,
    observeDarkTheme: ObserveDarkThemeUseCase,
    observeSharedFolders: ObserveSharedFoldersUseCase,
    private val setPort: SetPortUseCase,
    private val setDarkTheme: SetDarkThemeUseCase,
    private val addSharedFolder: AddSharedFolderUseCase,
    private val removeSharedFolder: RemoveSharedFolderUseCase
) : ViewModel() {

    private val portError = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        observePort(), observeDarkTheme(), observeSharedFolders(), portError
    ) { port, dark, folders, error ->
        SettingsUiState(
            port = port.value,
            portError = error,
            darkTheme = dark,
            sharedFolders = folders
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onPortChanged(text: String) {
        viewModelScope.launch {
            portError.value = setPort(text) == SetPortUseCase.Result.OutOfRange
        }
    }

    fun onDarkThemeChanged(enabled: Boolean) = viewModelScope.launch { setDarkTheme(enabled) }

    fun onFolderPicked(uri: String) = viewModelScope.launch { addSharedFolder(uri) }

    fun onFolderRemoved(uri: String) = viewModelScope.launch { removeSharedFolder(uri) }
}
