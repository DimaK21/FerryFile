package ru.kryu.ferryfile.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.ServerState

interface ServerRepository {

    val state: StateFlow<ServerState>

    suspend fun start()

    suspend fun stop()

    /** Останавливает сервер без повторного диспатча `ACTION_STOP` — вызывается самим сервисом. */
    suspend fun stopFromService()

    /** Пересобирает состояние по факту: сервер могли остановить из уведомления. */
    suspend fun refresh()
}
