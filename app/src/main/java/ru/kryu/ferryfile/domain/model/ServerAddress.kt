package ru.kryu.ferryfile.domain.model

data class ServerAddress(val host: String, val port: Port) {
    fun asUrl(): String = "http://$host:${port.value}"
}
