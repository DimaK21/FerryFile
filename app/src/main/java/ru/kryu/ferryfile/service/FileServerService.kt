package ru.kryu.ferryfile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // ServerRepository.refresh() is suspend and takes a mutex, so driving it from this Service
    // (which has no coroutine scope of its own) needs one; cancelled in onDestroy alongside the
    // engine stop below.
    @Inject
    lateinit var serverRepository: ServerRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val ACTION_START = "ru.kryu.ferryfile.START_SERVER"
        const val ACTION_STOP = "ru.kryu.ferryfile.STOP_SERVER"
        const val EXTRA_ADDRESS = "ru.kryu.ferryfile.EXTRA_ADDRESS"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ferryfile_server"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!ktorServer.isRunning) {
                val port = settings.port.value.value
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(intent.getStringExtra(EXTRA_ADDRESS)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                ktorServer.start(port)
            }
            ACTION_STOP -> {
                ktorServer.stop()
                // Stopping the engine directly (not via ServerRepository.stop()) never
                // publishes the change: nothing else observes ktorServer.isRunning on its own,
                // so the Home screen kept showing the last state it had, PIN included, until
                // something re-read it. ServerRepository.refresh() is exactly that self-healing
                // re-read (it sees the engine is down, revokes the PIN, and publishes Stopped)
                // — calling repository.stop() instead would re-enter here via launchService(
                // ACTION_STOP) and loop, so this drives refresh() instead.
                //
                // Started UNDISPATCHED rather than left at the default start mode: onStartCommand
                // runs synchronously on the main thread, and onDestroy() (which cancels
                // serviceScope below) cannot run until this call returns, so at the moment this
                // line executes the scope is guaranteed not yet cancelled. UNDISPATCHED begins
                // running refresh()'s body immediately, inline, right here — before stopSelf()
                // even runs — rather than merely scheduling it on Dispatchers.Default to start
                // at some later, unspecified time that a fast-enough onDestroy() could in
                // principle race. (refresh()'s own Stopped-path body — revoke() then a plain
                // property set — has no suspension point of its own beyond the mutex, so this
                // also means the call normally runs to completion here, not just to its start.)
                serviceScope.launch(start = CoroutineStart.UNDISPATCHED) { serverRepository.refresh() }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ktorServer.stop()
        serviceScope.cancel()
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
}
