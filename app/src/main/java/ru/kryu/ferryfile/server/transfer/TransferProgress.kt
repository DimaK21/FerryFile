package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferProgress @Inject constructor() {
    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val _isBusy = AtomicBoolean(false)
    val isBusy: Boolean get() = _isBusy.get()

    suspend fun emit(event: TransferEvent) = _events.emit(event)
    fun tryEmit(event: TransferEvent) { _events.tryEmit(event) }
    fun tryMarkBusy(): Boolean = _isBusy.compareAndSet(false, true)
    fun markIdle() = _isBusy.set(false)
}
