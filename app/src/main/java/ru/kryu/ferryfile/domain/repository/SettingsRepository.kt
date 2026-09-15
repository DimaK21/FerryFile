package ru.kryu.ferryfile.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder

interface SettingsRepository {

    val port: StateFlow<Port>

    val darkTheme: StateFlow<Boolean>

    val useHttps: StateFlow<Boolean>

    val sharedFolders: StateFlow<List<SharedFolder>>

    suspend fun setPort(port: Port)

    suspend fun setDarkTheme(enabled: Boolean)

    suspend fun setUseHttps(enabled: Boolean)

    /** @return `false`, если система не выдала постоянное разрешение на папку. */
    suspend fun addSharedFolder(uri: String): Boolean

    suspend fun removeSharedFolder(uri: String)
}
