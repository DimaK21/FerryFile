package ru.kryu.ferryfile.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.model.AccessPin
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.ServerAddress
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.ServerRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.domain.usecase.ObserveServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveSharedFoldersUseCase
import ru.kryu.ferryfile.domain.usecase.RefreshServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.StartServerUseCase
import ru.kryu.ferryfile.domain.usecase.StopServerUseCase

class HomeViewModelTest {

    private class FakeServerRepository(initial: ServerState) : ServerRepository {
        private val _state = MutableStateFlow(initial)
        override val state = _state.asStateFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun stopFromService() {}
        override suspend fun refresh() {}
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val port = MutableStateFlow(Port.DEFAULT)
        override val darkTheme = MutableStateFlow(false)
        override val useHttps = MutableStateFlow(false)
        override val sharedFolders =
            MutableStateFlow(listOf(SharedFolder("content://tree/a", "a")))
        override suspend fun setPort(port: Port) {}
        override suspend fun setDarkTheme(enabled: Boolean) {}
        override suspend fun setUseHttps(enabled: Boolean) {}
        override suspend fun addSharedFolder(uri: String) = true
        override suspend fun removeSharedFolder(uri: String) {}
    }

    private fun viewModel(state: ServerState): HomeViewModel {
        val server = FakeServerRepository(state)
        return HomeViewModel(
            ObserveServerStateUseCase(server),
            ObserveSharedFoldersUseCase(FakeSettingsRepository()),
            StartServerUseCase(server),
            StopServerUseCase(server),
            RefreshServerStateUseCase(server)
        )
    }

    @Test
    fun `stopping maps to a busy UI state without credentials`() = runTest {
        val uiState = viewModel(ServerState.Stopping).uiState.first { it.isStopping }

        assertTrue(uiState.isStopping)
        assertTrue(uiState.isBusy)
        assertFalse(uiState.isRunning)
        assertFalse(uiState.isStarting)
        assertEquals("", uiState.url)
        assertEquals("", uiState.pin)
        assertEquals("", uiState.certificateFingerprint)
        assertTrue(uiState.hasSharedFolders)
    }

    @Test
    fun `running still maps to credentials and stopped to the plain state`() = runTest {
        val running = viewModel(
            ServerState.Running(
                ServerAddress("10.0.0.5", Port.DEFAULT),
                AccessPin.of("123456"),
                "AA:BB"
            )
        ).uiState.first { it.isRunning }
        assertTrue(running.hasWifi)
        assertEquals("http://10.0.0.5:8080", running.url)
        assertEquals("123456", running.pin)
        assertEquals("AA:BB", running.certificateFingerprint)

        val stopped = viewModel(ServerState.Stopped).uiState.first { !it.isBusy && !it.isRunning }
        assertFalse(stopped.isStopping)
        assertEquals("", stopped.url)
    }
}
