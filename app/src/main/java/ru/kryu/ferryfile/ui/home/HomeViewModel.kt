package ru.kryu.ferryfile.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kryu.ferryfile.data.PreferencesRepository
import ru.kryu.ferryfile.server.KtorServer
import ru.kryu.ferryfile.service.FileServerService
import javax.inject.Inject

data class HomeUiState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val ipAddress: String = "",
    val qrBitmap: Bitmap? = null,
    val hasPassword: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository,
    private val ktorServer: KtorServer
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        val isRunning = ktorServer.isRunning
        val port = prefs.port
        val hasPassword = prefs.passwordHash.isNotEmpty()

        if (isRunning) {
            val ip = getWifiIpAddress(context)
            val qr = generateQr("http://$ip:$port")
            _uiState.value = HomeUiState(
                isRunning = true,
                port = port,
                ipAddress = ip,
                qrBitmap = qr,
                hasPassword = hasPassword
            )
        } else {
            _uiState.value = HomeUiState(
                isRunning = false,
                port = port,
                ipAddress = "",
                qrBitmap = null,
                hasPassword = hasPassword
            )
        }
    }

    fun startServer() {
        val port = prefs.port
        val intent = Intent(context, FileServerService::class.java).apply {
            action = FileServerService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)

        val ip = getWifiIpAddress(context)
        val qr = generateQr("http://$ip:$port")

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            port = port,
            ipAddress = ip,
            qrBitmap = qr
        )
    }

    fun stopServer() {
        val intent = Intent(context, FileServerService::class.java).apply {
            action = FileServerService.ACTION_STOP
        }
        context.startService(intent)

        _uiState.value = _uiState.value.copy(
            isRunning = false,
            ipAddress = "",
            qrBitmap = null
        )
    }

    fun refreshStatus() {
        val isRunning = ktorServer.isRunning
        val hasPassword = prefs.passwordHash.isNotEmpty()
        val port = prefs.port

        if (isRunning) {
            val ip = getWifiIpAddress(context)
            val qr = if (_uiState.value.qrBitmap == null) generateQr("http://$ip:$port") else _uiState.value.qrBitmap
            _uiState.value = _uiState.value.copy(
                isRunning = true,
                port = port,
                ipAddress = ip,
                qrBitmap = qr,
                hasPassword = hasPassword
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                port = port,
                ipAddress = "",
                qrBitmap = null,
                hasPassword = hasPassword
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(context: Context): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    private fun generateQr(content: String): Bitmap? {
        return try {
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bitmap ->
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }
}
