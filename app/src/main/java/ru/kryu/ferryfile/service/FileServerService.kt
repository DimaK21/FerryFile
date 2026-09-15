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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.domain.repository.ServerRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer
import java.net.URI
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!ktorServer.isRunning) {
                val advertisedAddress = intent.getStringExtra(EXTRA_ADDRESS)
                val parsedAddress = advertisedAddress
                    ?.let { runCatching { URI(it) }.getOrNull() }
                val port = parsedAddress?.port?.takeIf { it > 0 } ?: settings.port.value.value
                val useHttps = parsedAddress?.scheme?.equals("https", ignoreCase = true)
                    ?: settings.useHttps.value
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(advertisedAddress),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                try {
                    ktorServer.start(port, parsedAddress?.host, useHttps)
                } catch (cause: Exception) {
                    val protocol = if (useHttps) "HTTPS" else "HTTP"
                    Log.e(LOG_TAG, "Failed to start $protocol server", cause)
                    ktorServer.stop()
                    refreshAndStop()
                }
            }
            ACTION_STOP -> {
                ktorServer.stop()
                // Refresh before stopping the service so a refresh waiting for the repository
                // mutex cannot be cancelled by onDestroy().
                refreshAndStop()
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

    private fun refreshAndStop() {
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                serverRepository.refresh()
            } finally {
                stopSelf()
            }
        }
    }

}
