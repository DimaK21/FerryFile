package ru.kryu.ferryfile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
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

    // Set from the moment ACTION_STOP (or the system time-limit callback) is accepted until a
    // fresh ACTION_START is accepted:
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
                // A fresh start supersedes the "stopped by the time limit" message.
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_TIME_LIMIT)
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

    // Android 15+ (API 35): a dataSync foreground service may run for about 6 h per 24 h while the
    // app is in the background (the timer resets when the user brings the app to the foreground).
    // When the budget is spent the system calls this on the main thread and crashes the app
    // unless the service calls stopSelf() within a few seconds. Never called on older releases.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
            super.onTimeout(startId, fgsType)
            return
        }
        Log.w(LOG_TAG, "dataSync foreground service time limit reached; stopping the server")
        stopRequested = true
        // Deliberately not through `commands`: a Start or a slow Stop ahead in the queue must not
        // delay stopSelf() past the platform deadline. stopRequested keeps a queued Start from
        // resurrecting the engine.
        serviceScope.launch {
            try {
                if (!stopServerForTimeLimit(serviceScope, serverRepository)) {
                    Log.w(
                        LOG_TAG,
                        "Server shutdown still running after $TIME_LIMIT_STOP_BUDGET_MS ms; " +
                            "releasing the foreground service (onDestroy waits for the engine)"
                    )
                }
                notifyTimeLimitReached()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Log.e(LOG_TAG, "Failed to finish the time-limit stop", cause)
            } finally {
                // Whatever went wrong above, the service must still be stopped or the system
                // crashes the app. Unconditional stopSelf(), not stopSelfResult(startId): a newer
                // start request must not keep a foreground service alive that the system has
                // already timed out.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyTimeLimitReached() {
        val text = getString(R.string.notification_time_limit_stopped)
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.brand_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // Separate id: the foreground notification (NOTIFICATION_ID) disappears with the service.
        // Silently dropped by the system if POST_NOTIFICATIONS was not granted.
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_TIME_LIMIT, notification)
    }

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

    companion object {
        const val ACTION_START = "ru.kryu.ferryfile.START_SERVER"
        const val ACTION_STOP = "ru.kryu.ferryfile.STOP_SERVER"
        const val EXTRA_ADDRESS = "ru.kryu.ferryfile.EXTRA_ADDRESS"
        const val EXTRA_PORT = "ru.kryu.ferryfile.EXTRA_PORT"
        const val EXTRA_HOST = "ru.kryu.ferryfile.EXTRA_HOST"
        const val EXTRA_USE_HTTPS = "ru.kryu.ferryfile.EXTRA_USE_HTTPS"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_ID_TIME_LIMIT = 2
        const val CHANNEL_ID = "ferryfile_server"
        const val LOG_TAG = "FerryFileServer"
    }
}
