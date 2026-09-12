package ru.kryu.ferryfile.data.network

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kryu.ferryfile.domain.repository.NetworkRepository
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiNetworkRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkRepository {

    override suspend fun localAddress(): String? = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext null
        val network = manager.activeNetwork ?: return@withContext null
        val properties = manager.getLinkProperties(network) ?: return@withContext null
        properties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
