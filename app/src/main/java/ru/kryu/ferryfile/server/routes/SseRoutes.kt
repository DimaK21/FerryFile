package ru.kryu.ferryfile.server.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.kryu.ferryfile.server.transfer.TransferEvent
import ru.kryu.ferryfile.server.transfer.TransferProgress

fun Application.configureSseRoutes(transferProgress: TransferProgress) {
    routing {
        authenticate("session") {
            sse("/api/progress") {
                transferProgress.events.collect { event ->
                    val (name, data) = when (event) {
                        is TransferEvent.Progress -> "progress" to Json.encodeToString(event)
                        is TransferEvent.Done -> "done" to Json.encodeToString(event)
                        is TransferEvent.Error -> "error" to Json.encodeToString(event)
                    }
                    send(ServerSentEvent(data = data, event = name))
                }
            }
        }
    }
}
