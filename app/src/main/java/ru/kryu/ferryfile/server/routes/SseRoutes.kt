package ru.kryu.ferryfile.server.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.kryu.ferryfile.domain.model.TransferEvent
import ru.kryu.ferryfile.server.transfer.TransferProgress

@Serializable
private data class ProgressDto(
    val transferId: String,
    val file: String,
    val bytes: Long,
    val total: Long,
    val pct: Int,
    val eta: Int
)

@Serializable
private data class DoneDto(val transferId: String, val files: Int, val bytes: Long)

@Serializable
private data class ErrorDto(val transferId: String, val code: String, val message: String)

fun Application.configureSseRoutes(transferProgress: TransferProgress) {
    routing {
        authenticate("session") {
            sse("/api/progress") {
                transferProgress.events.collect { event ->
                    val (name, data) = when (event) {
                        is TransferEvent.Progress -> "progress" to Json.encodeToString(
                            ProgressDto(
                                event.transferId, event.file, event.bytes,
                                event.total, event.pct, event.etaSeconds
                            )
                        )

                        is TransferEvent.Done -> "done" to Json.encodeToString(
                            DoneDto(event.transferId, event.files, event.bytes)
                        )

                        is TransferEvent.Error -> "error" to Json.encodeToString(
                            ErrorDto(event.transferId, event.code, event.message)
                        )
                    }
                    send(ServerSentEvent(data = data, event = name))
                }
            }
        }
    }
}
