package ru.kryu.ferryfile.server.transfer

import kotlinx.serialization.Serializable

@Serializable
sealed class TransferEvent {
    @Serializable
    data class Progress(val file: String, val bytes: Long, val total: Long, val pct: Int, val eta: Int) : TransferEvent()
    @Serializable
    data class Done(val files: Int, val bytes: Long) : TransferEvent()
    @Serializable
    data class Error(val code: String, val message: String) : TransferEvent()
}
