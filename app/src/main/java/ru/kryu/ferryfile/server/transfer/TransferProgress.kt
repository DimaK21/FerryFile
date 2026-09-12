package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.kryu.ferryfile.domain.model.TransferEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шина событий передачи. Загрузка (upload) больше не использует глобальную блокировку:
 * параллельные передачи различаются по [TransferEvent.transferId], который генерирует клиент.
 *
 * `isBusy`/`tryMarkBusy`/`markIdle`/`tryEmit` остаются только ради `/api/download`
 * (см. FileRoutes.kt), который их ещё использует — Задача 7 их уберёт вместе с переписыванием
 * маршрута скачивания.
 */
@Singleton
class TransferProgress @Inject constructor() {

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val _isBusy = AtomicBoolean(false)
    val isBusy: Boolean get() = _isBusy.get()

    fun tryEmit(event: TransferEvent) {
        _events.tryEmit(event)
    }

    fun tryMarkBusy(): Boolean = _isBusy.compareAndSet(false, true)
    fun markIdle() = _isBusy.set(false)

    fun emitProgress(
        transferId: String,
        file: String,
        bytes: Long,
        total: Long,
        startedAtMillis: Long
    ) {
        val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        _events.tryEmit(
            TransferEvent.Progress(
                transferId = transferId,
                file = file,
                bytes = bytes,
                total = total,
                pct = pct,
                etaSeconds = etaSeconds(bytes, total, startedAtMillis)
            )
        )
    }

    fun emitDone(transferId: String, files: Int, bytes: Long) {
        _events.tryEmit(TransferEvent.Done(transferId, files, bytes))
    }

    fun emitError(transferId: String, code: String, message: String) {
        _events.tryEmit(TransferEvent.Error(transferId, code, message))
    }

    private fun etaSeconds(bytes: Long, total: Long, startedAtMillis: Long): Int {
        if (total <= 0 || bytes <= 0 || startedAtMillis <= 0L) return UNKNOWN_ETA
        val elapsed = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(1L)
        val remaining = (total - bytes).coerceAtLeast(0L)
        return ((remaining * elapsed) / bytes / 1000L).toInt()
    }

    private companion object {
        const val UNKNOWN_ETA = -1
    }
}
