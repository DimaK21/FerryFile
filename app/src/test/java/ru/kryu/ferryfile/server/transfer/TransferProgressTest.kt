package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {

    @Test fun `emitted event is received by collector`() = runTest {
        val progress = TransferProgress()
        val event = TransferEvent.Progress("test.zip", 50L, 100L, 50, 2)
        var received: TransferEvent? = null
        val job = launch { received = progress.events.first() }
        yield() // let the collector subscribe before emitting
        progress.emit(event)
        job.join()
        assertEquals(event, received)
    }

    @Test fun `isBusy starts false`() = runTest {
        assertFalse(TransferProgress().isBusy)
    }

    @Test fun `markBusy and markIdle toggle isBusy`() = runTest {
        val p = TransferProgress()
        p.markBusy(); assertTrue(p.isBusy)
        p.markIdle(); assertFalse(p.isBusy)
    }
}
