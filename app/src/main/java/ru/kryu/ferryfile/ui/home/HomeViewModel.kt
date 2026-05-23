package ru.kryu.ferryfile.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.data.PreferencesRepository
import ru.kryu.ferryfile.server.KtorServer
import ru.kryu.ferryfile.service.FileServerService
import java.net.Inet4Address
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
        _uiState.value = HomeUiState(isRunning = isRunning, port = port, hasPassword = hasPassword)
        if (isRunning) {
            viewModelScope.launch(Dispatchers.Default) {
                val ip = getWifiIpAddress()
                val qr = if (ip.isNotEmpty()) generateQr("http://$ip:$port") else null
                _uiState.update { it.copy(ipAddress = ip, qrBitmap = qr) }
            }
        }
    }

    fun startServer() {
        val port = prefs.port
        ContextCompat.startForegroundService(
            context,
            Intent(context, FileServerService::class.java).apply { action = FileServerService.ACTION_START }
        )
        _uiState.update { it.copy(isRunning = true, port = port) }
        viewModelScope.launch(Dispatchers.Default) {
            val ip = getWifiIpAddress()
            val qr = if (ip.isNotEmpty()) generateQr("http://$ip:$port") else null
            _uiState.update { it.copy(ipAddress = ip, qrBitmap = qr) }
        }
    }

    fun stopServer() {
        context.stopService(Intent(context, FileServerService::class.java))
        _uiState.update { it.copy(isRunning = false, ipAddress = "", qrBitmap = null) }
    }

    fun refreshStatus() {
        val isRunning = ktorServer.isRunning
        val hasPassword = prefs.passwordHash.isNotEmpty()
        val port = prefs.port
        _uiState.update { it.copy(isRunning = isRunning, port = port, hasPassword = hasPassword) }
        if (isRunning) {
            val current = _uiState.value
            val needsRefresh = current.qrBitmap == null || current.port != port
            if (needsRefresh) {
                viewModelScope.launch(Dispatchers.Default) {
                    val ip = getWifiIpAddress()
                    val qr = if (ip.isNotEmpty()) generateQr("http://$ip:$port") else null
                    _uiState.update { it.copy(ipAddress = ip, qrBitmap = qr) }
                }
            }
        } else {
            _uiState.update { it.copy(ipAddress = "", qrBitmap = null) }
        }
    }

    private fun getWifiIpAddress(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return ""
            val props = cm.getLinkProperties(network) ?: return ""
            props.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress ?: ""
        } else {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) return ""
            String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        }
    }

    private fun generateQr(content: String): Bitmap? {
        return try {
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height) { i ->
                if (bitMatrix[i % width, i / width]) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bmp ->
                bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }
}
