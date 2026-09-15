package ru.kryu.ferryfile.domain.model

data class ServerAddress(
    val host: String,
    val port: Port,
    val useHttps: Boolean = false
) {
    fun asUrl(): String {
        val urlHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val scheme = if (useHttps) "https" else "http"
        return "$scheme://$urlHost:${port.value}"
    }
}
