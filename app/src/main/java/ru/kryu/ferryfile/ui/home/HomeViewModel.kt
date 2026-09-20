package ru.kryu.ferryfile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.usecase.ObserveServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveSharedFoldersUseCase
import ru.kryu.ferryfile.domain.usecase.RefreshServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.StartServerUseCase
import ru.kryu.ferryfile.domain.usecase.StopServerUseCase
import javax.inject.Inject

data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val url: String = "",
    val pin: String = "",
    val certificateFingerprint: String = "",
    val hasWifi: Boolean = true,
    val hasSharedFolders: Boolean = true
) {
    val isBusy: Boolean get() = isStarting || isStopping
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeServerState: ObserveServerStateUseCase,
    observeSharedFolders: ObserveSharedFoldersUseCase,
    private val startServer: StartServerUseCase,
    private val stopServer: StopServerUseCase,
    private val refreshServerState: RefreshServerStateUseCase
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(observeServerState(), observeSharedFolders()) { server, folders ->
            when (server) {
                is ServerState.Stopped -> HomeUiState(hasSharedFolders = folders.isNotEmpty())
                is ServerState.Starting -> HomeUiState(
                    isStarting = true,
                    hasSharedFolders = folders.isNotEmpty()
                )
                is ServerState.Stopping -> HomeUiState(
                    isStopping = true,
                    hasSharedFolders = folders.isNotEmpty()
                )
                is ServerState.Running -> HomeUiState(
                    isRunning = true,
                    url = server.address?.asUrl().orEmpty(),
                    pin = server.pin.digits,
                    certificateFingerprint = server.certificateFingerprint,
                    hasWifi = server.address != null,
                    hasSharedFolders = folders.isNotEmpty()
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onStartClicked() = viewModelScope.launch { startServer() }

    fun onStopClicked() = viewModelScope.launch { stopServer() }

    fun refresh() = viewModelScope.launch { refreshServerState() }
}
