package ru.kryu.ferryfile.domain.model

sealed interface ServerState {

    data object Stopped : ServerState

    data object Starting : ServerState

    /** [address] равен `null`, когда сервер запущен, но устройство не в Wi-Fi-сети. */
    data class Running(val address: ServerAddress?, val pin: AccessPin) : ServerState
}
