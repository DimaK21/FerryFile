package ru.kryu.ferryfile.data.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
open class ServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val network: NetworkRepository,
    private val accessCodes: AccessCodeRepository,
    private val server: KtorServer
) : ServerRepository {

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    override val state: StateFlow<ServerState> = _state.asStateFlow()

    // start/stop/refresh all read-then-write `state` and `accessCodes` across a suspending
    // network lookup; without serialization a refresh() landing inside that suspension could
    // revoke the PIN a concurrent start() is about to publish (see ServerRepositoryImplTest).
    private val mutex = Mutex()

    override suspend fun start(): Unit = mutex.withLock {
        if (server.isRunning) {
            refreshLocked()
            return@withLock
        }
        _state.value = ServerState.Starting
        accessCodes.issue()
        // Resolve the address before starting the service so it can go straight into the
        // notification text as an intent extra, rather than the service re-deriving it.
        val resolvedAddress = address()
        launchService(FileServerService.ACTION_START, resolvedAddress?.asUrl())
        // Re-read the current PIN rather than trusting a value captured before the suspending
        // address() lookup: nothing can revoke it while the mutex is held, but publishing
        // whatever is actually current (and failing closed if it is somehow gone) is cheap
        // insurance against ever showing a PIN that verify() would reject.
        _state.value = accessCodes.current?.let { ServerState.Running(resolvedAddress, it) }
            ?: ServerState.Stopped
    }

    override suspend fun stop(): Unit = mutex.withLock {
        launchService(FileServerService.ACTION_STOP)
        accessCodes.revoke()
        _state.value = ServerState.Stopped
    }

    override suspend fun refresh(): Unit = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        _state.value = if (!server.isRunning) {
            accessCodes.revoke()
            ServerState.Stopped
        } else {
            ServerState.Running(address(), accessCodes.current ?: accessCodes.issue())
        }
    }

    private suspend fun address(): ServerAddress? =
        network.localAddress()?.let { ServerAddress(it, settings.port.value) }

    /**
     * Starts or stops the foreground service. Open + protected so tests can no-op the Android
     * side effect while still exercising the real start/stop/refresh coordination above.
     */
    protected open fun launchService(action: String, address: String? = null) {
        val intent = Intent(context, FileServerService::class.java).apply {
            this.action = action
            if (address != null) putExtra(FileServerService.EXTRA_ADDRESS, address)
        }
        if (action == FileServerService.ACTION_START) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
