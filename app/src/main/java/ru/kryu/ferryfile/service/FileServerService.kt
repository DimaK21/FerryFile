package ru.kryu.ferryfile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.domain.repository.ServerRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer
import javax.inject.Inject

@AndroidEntryPoint
class FileServerService : Service() {

    @Inject
    lateinit var ktorServer: KtorServer

    @Inject
    lateinit var settings: SettingsRepository

    // ServerRepository.refresh() is suspend and takes a mutex, so command processing needs a
    // service-owned scope and an ordered queue.
    @Inject
    lateinit var serverRepository: ServerRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<ServerCommand>(Channel.UNLIMITED)

    // Set from the moment ACTION_STOP is accepted until a fresh ACTION_START is accepted:
    // not just a duplicate-click guard — after a stop, KtorServer.isRunning flips to false
    // *before* the blocking shutdown returns, so a Start still queued in `commands` could
    // otherwise pass the isRunning check and resurrect the server mid-stop.
    @Volatile
    private var stopRequested = false

    private sealed interface ServerCommand {
        data class Start(
            val port: Int,
            val host: String?,
            val useHttps: Boolean,
            val startId: Int
        ) : ServerCommand

        data class Stop(val startId: Int) : ServerCommand
    }

    companion object {
        const val ACTION_START = "ru.kryu.ferryfile.START_SERVER"
        const val ACTION_STOP = "ru.kryu.ferryfile.STOP_SERVER"
        const val EXTRA_ADDRESS = "ru.kryu.ferryfile.EXTRA_ADDRESS"
        const val EXTRA_PORT = "ru.kryu.ferryfile.EXTRA_PORT"
        const val EXTRA_HOST = "ru.kryu.ferryfile.EXTRA_HOST"
        const val EXTRA_USE_HTTPS = "ru.kryu.ferryfile.EXTRA_USE_HTTPS"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ferryfile_server"
        const val LOG_TAG = "FerryFileServer"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        serviceScope.launch { processCommands() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val advertisedAddress = intent.getStringExtra(EXTRA_ADDRESS)
                val port = intent.getIntExtra(EXTRA_PORT, settings.port.value.value)
                    .takeIf { it > 0 }
                    ?: settings.port.value.value
                val useHttps = if (intent.hasExtra(EXTRA_USE_HTTPS)) {
                    intent.getBooleanExtra(EXTRA_USE_HTTPS, false)
                } else {
                    settings.useHttps.value
                }
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(advertisedAddress),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (cause: Exception) {
                    Log.e(LOG_TAG, "Failed to promote server service to foreground", cause)
                    serviceScope.launch { refreshAndStop(startId) }
                    return START_NOT_STICKY
                }
                stopRequested = false
                commands.trySend(
                    ServerCommand.Start(
                        port = port,
                        host = intent.getStringExtra(EXTRA_HOST),
                        useHttps = useHttps,
                        startId = startId
                    )
                )
            }
            ACTION_STOP -> {
                stopRequested = true
                commands.trySend(ServerCommand.Stop(startId))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        commands.close()
        serviceScope.cancel()
        // Do not return from onDestroy until the engine is closed. A detached coroutine can be
        // killed with the process immediately after the service teardown callback returns.
        runBlocking(Dispatchers.IO) {
            try {
                ktorServer.stop()
            } catch (cause: Exception) {
                Log.e(LOG_TAG, "Failed to stop server during service teardown", cause)
            }
            // Best-effort reconciliation so the UI is not left showing Running/Stopping after an
            // abnormal teardown. The blocking ktorServer.stop() above is pre-existing by design
            // (bounded by Netty's own shutdown timeouts); this timeout additionally bounds the
            // repository mutex wait. A rare timeout is covered by the ON_RESUME refresh on the
            // next foreground.
            runCatching { withTimeoutOrNull(2_000) { serverRepository.refresh() } }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(address: String?): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, FileServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.brand_name))
            .setContentText(address ?: getString(R.string.notification_no_wifi_connection))
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private suspend fun processCommands() {
        for (command in commands) {
            when (command) {
                is ServerCommand.Start -> processStart(command)
                is ServerCommand.Stop -> processStop(command)
            }
        }
    }

    private suspend fun processStart(command: ServerCommand.Start) {
        if (stopRequested) return // a queued Stop will finalize the state through the repository
        if (ktorServer.isRunning) return

        try {
            ktorServer.start(command.port, command.host, command.useHttps)
            if (stopRequested) {
                // Stop was accepted while the engine was still binding. Finalize it here instead
                // of calling refresh(), so a Running state is never published after a stop — the
                // queued Stop command then finds everything already stopped.
                serverRepository.stopFromService()
                return
            }
            // Reconcile after the real bind completes. Repository.start() dispatches this
            // command asynchronously, so a foreground refresh can otherwise revoke its PIN
            // before Netty has finished starting.
            serverRepository.refresh()
        } catch (cause: Exception) {
            val protocol = if (command.useHttps) "HTTPS" else "HTTP"
            Log.e(LOG_TAG, "Failed to start $protocol server", cause)
            try {
                ktorServer.stop()
            } catch (cleanupCause: Exception) {
                Log.e(LOG_TAG, "Failed to clean up after start failure", cleanupCause)
            }
            refreshAndStop(command.startId)
        }
    }

    // Runs under repository.mutex, so a repository.stop() holding the lock is never interleaved.
    // The repository's final _state.value = Stopped happens-before this stopSelfResult; the
    // processStop -> UI Stopped ordering is a property of that shared lock, not a guarantee
    // that the collection coroutine wins any race.
    private suspend fun processStop(command: ServerCommand.Stop) {
        try {
            serverRepository.stopFromService()
        } catch (cause: Exception) {
            Log.e(LOG_TAG, "Failed to stop server via repository", cause)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stopSelfResult(command.startId)
            }
        }
    }

    private suspend fun refreshAndStop(startId: Int) {
        try {
            serverRepository.refresh()
        } catch (cause: Exception) {
            Log.e(LOG_TAG, "Failed to refresh server state after stop", cause)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stopSelfResult(startId)
            }
        }
    }

}
