package ru.kryu.ferryfile.domain.model

/**
 * Событие передачи. [transferId] генерирует клиент, чтобы несколько вкладок браузера
 * не видели чужой прогресс. Сериализация — забота HTTP-слоя, здесь её нет.
 */
sealed interface TransferEvent {

    val transferId: String

    data class Progress(
        override val transferId: String,
        val file: String,
        val bytes: Long,
        val total: Long,
        val pct: Int,
        val etaSeconds: Int
    ) : TransferEvent

    data class Done(
        override val transferId: String,
        val files: Int,
        val bytes: Long
    ) : TransferEvent

    data class Error(
        override val transferId: String,
        val code: String,
        val message: String
    ) : TransferEvent
}
