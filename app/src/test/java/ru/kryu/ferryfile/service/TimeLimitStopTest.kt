package ru.kryu.ferryfile.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.ServerRepository

private class FakeServerRepository(
    private val onStopFromService: suspend () -> Unit = {}
) : ServerRepository {
    override val state: StateFlow<ServerState> = MutableStateFlow(ServerState.Stopped)

    var stopFromServiceCalls = 0
    var stopCalls = 0
    var stopCompleted = false
    var stopCancelled = false

    override suspend fun start() = error("not used")

    // The UI-origin stop would dispatch ACTION_STOP back into the service being torn down.
    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun stopFromService() {
        stopFromServiceCalls++
        try {
            onStopFromService()
            stopCompleted = true
        } catch (cancelled: CancellationException) {
            stopCancelled = true
            throw cancelled
        }
    }

    override suspend fun refresh() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class TimeLimitStopTest {

    @Test
    fun `stops through the service-origin repository stop and never the UI-origin one`() = runTest {
        val repo = FakeServerRepository()

        val finished = stopServerForTimeLimit(backgroundScope, repo, budgetMs = 3_000)

        assertTrue(finished)
        assertEquals(1, repo.stopFromServiceCalls)
        assertEquals(0, repo.stopCalls)
        assertTrue(repo.stopCompleted)
    }

    @Test
    fun `returns as soon as the shutdown completes instead of waiting out the budget`() = runTest {
        val repo = FakeServerRepository { delay(200) }

        val finished = stopServerForTimeLimit(backgroundScope, repo, budgetMs = 3_000)

        assertTrue(finished)
        assertEquals(200, testScheduler.currentTime)
    }

    @Test
    fun `releases the caller when the budget elapses and lets the shutdown keep running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeServerRepository { gate.await() }

        val finished = stopServerForTimeLimit(backgroundScope, repo, budgetMs = 3_000)

        // The platform kills the process if stopSelf() is late, so a slow Netty shutdown must not
        // hold the caller past the budget...
        assertFalse(finished)
        assertEquals(3_000, testScheduler.currentTime)
        // ...but abandoning the wait must not abandon the shutdown itself.
        assertFalse(repo.stopCancelled)
        gate.complete(Unit)
        runCurrent()
        assertTrue(repo.stopCompleted)
    }

    @Test
    fun `a failing shutdown does not propagate so the service can still be stopped`() = runTest {
        val failure = IllegalStateException("engine refused to stop")
        val repo = FakeServerRepository { throw failure }
        val loggedErrors = mutableListOf<Throwable>()

        val finished = stopServerForTimeLimit(
            backgroundScope,
            repo,
            budgetMs = 3_000,
            logError = { _, cause -> loggedErrors += cause }
        )

        assertTrue(finished)
        assertEquals(1, repo.stopFromServiceCalls)
        assertEquals(listOf<Throwable>(failure), loggedErrors)
    }
}
