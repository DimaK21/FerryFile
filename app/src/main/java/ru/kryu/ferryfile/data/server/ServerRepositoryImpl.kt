package ru.kryu.ferryfile.data.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kryu.ferryfile.domain.model.Port
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
    private var activeUseHttps = false
    private var activePort: Port? = null

    override suspend fun start(): Unit = mutex.withLock {
        if (server.isRunning) {
            refreshLocked()
            return@withLock
        }
        if (_state.value is ServerState.Starting) {
            return@withLock // дубль Start, пока сервис ещё не подтвердил bind
        }
        _state.value = ServerState.Starting
        var published = false
        try {
            accessCodes.issue()
            // Snapshot the complete configuration before dispatching the service. The service may
            // process the Intent later, after settings or network state have changed.
            val useHttps = settings.useHttps.value
            val port = settings.port.value
            val resolvedAddress = address(useHttps, port)
            // Generate/load the certificate before dispatching the service. Apart from keeping
            // RSA work off the service's main thread, this guarantees that the state fingerprint
            // belongs to the exact keystore the server will use.
            if (useHttps) {
                withContext(Dispatchers.IO) {
                    server.prepareTls(resolvedAddress?.host)
                }
            }
            launchService(
                action = FileServerService.ACTION_START,
                address = resolvedAddress?.asUrl(),
                port = port.value,
                host = resolvedAddress?.host,
                useHttps = useHttps
            )
            activeUseHttps = useHttps
            activePort = port
            // The service publishes Running after Netty has actually bound the socket. Until
            // then the UI stays in Starting and cannot expose an address/PIN for a failed start.
            published = true
        } finally {
            if (!published) {
                accessCodes.revoke()
                activeUseHttps = false
                activePort = null
                _state.value = ServerState.Stopped
            }
        }
    }

    override suspend fun stop(): Unit = mutex.withLock {
        stopInternal(dispatchService = true)
    }

    override suspend fun stopFromService(): Unit = mutex.withLock {
        stopInternal(dispatchService = false)
    }

    // Publishing Stopped before dispatching ACTION_STOP (rather than tearing the service down
    // first) is deliberate: the UI learns the truth as soon as the engine is actually gone, and
    // the service call is a pure notification-channel teardown that must not loop back here.
    private suspend fun stopInternal(dispatchService: Boolean) {
        // Stopping only while the engine really lives: stopFromService() can arrive after the
        // UI-initiated stop already tore everything down, or while a stale Start sits in the
        // service command queue.
        if (server.isRunning) {
            _state.value = ServerState.Stopping
        }
        try {
            // Wait for the actual engine shutdown before publishing stopped. This also
            // keeps the blocking Netty shutdown away from the caller's main thread.
            server.stop()
        } finally {
            accessCodes.revoke()
            activeUseHttps = false
            activePort = null
            _state.value = ServerState.Stopped
            if (dispatchService) {
                launchService(FileServerService.ACTION_STOP)
            }
        }
    }

    override suspend fun refresh(): Unit = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        if (!server.isRunning) {
            accessCodes.revoke()
            activeUseHttps = false
            activePort = null
            _state.value = ServerState.Stopped
            return
        }

        val port = activePort ?: settings.port.value
        val currentAddress = address(activeUseHttps, port)
        if (
            activeUseHttps &&
            currentAddress != null &&
            server.currentTlsHost() != currentAddress.host
        ) {
            restartForAddress(currentAddress, port)
            return
        }

        _state.value = ServerState.Running(
            currentAddress,
            accessCodes.current ?: accessCodes.issue(),
            if (activeUseHttps) server.currentTlsFingerprint().orEmpty() else ""
        )
    }

    private suspend fun restartForAddress(address: ServerAddress, port: Port) {
        _state.value = ServerState.Starting
        var published = false
        try {
            server.stop()
            val fingerprint = withContext(Dispatchers.IO) {
                server.prepareTls(address.host).orEmpty()
            }
            launchService(
                action = FileServerService.ACTION_START,
                address = address.asUrl(),
                port = port.value,
                host = address.host,
                useHttps = true
            )
            activeUseHttps = true
            activePort = port
            _state.value = ServerState.Running(
                address,
                accessCodes.current ?: accessCodes.issue(),
                fingerprint
            )
            published = true
        } finally {
            if (!published) {
                accessCodes.revoke()
                activeUseHttps = false
                activePort = null
                _state.value = ServerState.Stopped
                // The old engine was already stopped before restart preparation. Make sure the
                // foreground service is also torn down if certificate generation or dispatching
                // the replacement start command fails.
                runCatching { launchService(FileServerService.ACTION_STOP) }
            }
        }
    }

    private suspend fun address(useHttps: Boolean, port: Port): ServerAddress? =
        network.localAddress()?.let { ServerAddress(it, port, useHttps) }

    /**
     * Starts or stops the foreground service. Open + protected so tests can no-op the Android
     * side effect while still exercising the real start/stop/refresh coordination above.
     */
    protected open fun launchService(
        action: String,
        address: String? = null,
        port: Int? = null,
        host: String? = null,
        useHttps: Boolean? = null
    ) {
        val intent = Intent(context, FileServerService::class.java).apply {
            this.action = action
            if (address != null) putExtra(FileServerService.EXTRA_ADDRESS, address)
            if (port != null) putExtra(FileServerService.EXTRA_PORT, port)
            if (host != null) putExtra(FileServerService.EXTRA_HOST, host)
            if (useHttps != null) putExtra(FileServerService.EXTRA_USE_HTTPS, useHttps)
        }
        if (action == FileServerService.ACTION_START) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
