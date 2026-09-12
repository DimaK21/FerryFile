package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.ServerRepository
import javax.inject.Inject

class ObserveServerStateUseCase @Inject constructor(private val server: ServerRepository) {
    operator fun invoke(): StateFlow<ServerState> = server.state
}

class StartServerUseCase @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke() = server.start()
}

class StopServerUseCase @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke() = server.stop()
}

class RefreshServerStateUseCase @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke() = server.refresh()
}
