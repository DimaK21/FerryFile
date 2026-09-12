package ru.kryu.ferryfile.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.ServerState

interface ServerRepository {

    val state: StateFlow<ServerState>

    suspend fun start()

    suspend fun stop()

    /** Пересобирает состояние по факту: сервер могли остановить из уведомления. */
    suspend fun refresh()
}
