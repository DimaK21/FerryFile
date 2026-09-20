package ru.kryu.ferryfile.domain.model

sealed interface ServerState {

    data object Stopped : ServerState

    data object Starting : ServerState

    /** Идёт graceful shutdown Netty; адрес и PIN уже не показываются. */
    data object Stopping : ServerState

    /** [address] равен `null`, когда сервер запущен, но устройство не в Wi-Fi-сети. */
    data class Running(
        val address: ServerAddress?,
        val pin: AccessPin,
        val certificateFingerprint: String = ""
    ) : ServerState
}
