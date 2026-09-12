package ru.kryu.ferryfile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer
import javax.inject.Inject

@AndroidEntryPoint
class FileServerService : Service() {

    @Inject
    lateinit var ktorServer: KtorServer

    @Inject
    lateinit var settings: SettingsRepository

    companion object {
        const val ACTION_START = "ru.kryu.ferryfile.START_SERVER"
        const val ACTION_STOP = "ru.kryu.ferryfile.STOP_SERVER"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ferryfile_server"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "FerryFile Server", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!ktorServer.isRunning) {
                val port = settings.port.value.value
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(port),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                ktorServer.start(port)
            }
            ACTION_STOP -> {
                ktorServer.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ktorServer.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(port: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FerryFile")
            .setContentText("Server running on port $port")
            .setOngoing(true)
            .build()
    }
}
