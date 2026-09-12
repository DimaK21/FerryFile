package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.model.TransferEvent

class TransferProgressTest {

    private val progress = TransferProgress()

    @Test fun `progress carries percentage computed from the total size`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitProgress("tx-1", "movie.mp4", bytes = 512, total = 2048, startedAtMillis = 0L)

        val event = received.await() as TransferEvent.Progress
        assertEquals("tx-1", event.transferId)
        assertEquals("movie.mp4", event.file)
        assertEquals(25, event.pct)
    }

    @Test fun `percentage is zero when the total size is unknown`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitProgress("tx-1", "movie.mp4", bytes = 512, total = -1, startedAtMillis = 0L)

        assertEquals(0, (received.await() as TransferEvent.Progress).pct)
    }

    @Test fun `percentage never exceeds one hundred`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitProgress("tx-1", "movie.mp4", bytes = 4096, total = 2048, startedAtMillis = 0L)

        assertEquals(100, (received.await() as TransferEvent.Progress).pct)
    }

    @Test fun `done reports the number of files and bytes`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitDone("tx-2", files = 3, bytes = 900)

        val event = received.await() as TransferEvent.Done
        assertEquals("tx-2", event.transferId)
        assertEquals(3, event.files)
        assertEquals(900L, event.bytes)
    }

    @Test fun `error carries the code and message`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitError("tx-3", "upload_failed", "disk full")

        val event = received.await() as TransferEvent.Error
        assertEquals("upload_failed", event.code)
        assertEquals("disk full", event.message)
    }
}
