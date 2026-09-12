package ru.kryu.ferryfile.data.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kryu.ferryfile.domain.model.ServerAddress
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.AccessCodeRepository
import ru.kryu.ferryfile.domain.repository.NetworkRepository
import ru.kryu.ferryfile.domain.repository.ServerRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer
import ru.kryu.ferryfile.service.FileServerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val network: NetworkRepository,
    private val accessCodes: AccessCodeRepository,
    private val server: KtorServer
) : ServerRepository {

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    override val state: StateFlow<ServerState> = _state.asStateFlow()

    override suspend fun start() {
        if (server.isRunning) return refresh()
        _state.value = ServerState.Starting
        val pin = accessCodes.issue()
        ContextCompat.startForegroundService(context, intent(FileServerService.ACTION_START))
        _state.value = ServerState.Running(address(), pin)
    }

    override suspend fun stop() {
        context.startService(intent(FileServerService.ACTION_STOP))
        accessCodes.revoke()
        _state.value = ServerState.Stopped
    }

    override suspend fun refresh() {
        _state.value = if (!server.isRunning) {
            accessCodes.revoke()
            ServerState.Stopped
        } else {
            ServerState.Running(address(), accessCodes.current ?: accessCodes.issue())
        }
    }

    private suspend fun address(): ServerAddress? =
        network.localAddress()?.let { ServerAddress(it, settings.port.value) }

    private fun intent(action: String) =
        Intent(context, FileServerService::class.java).apply { this.action = action }
}
