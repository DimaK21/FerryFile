package ru.kryu.ferryfile.data.server

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.NetworkRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer

/** [NetworkRepository] whose [localAddress] suspends until the test explicitly [release]s it. */
private class GatedNetworkRepository : NetworkRepository {
    private val gate = CompletableDeferred<Unit>()
    override suspend fun localAddress(): String? {
        gate.await()
        return "10.0.0.5"
    }
    fun release() {
        gate.complete(Unit)
    }
}

class ServerRepositoryImplTest {

    private val context: Context = mock()
    private val settings: SettingsRepository = mock()
    private val server: KtorServer = mock()

    private fun repo(network: NetworkRepository, accessCodes: InMemoryAccessCodeRepository) =
        object : ServerRepositoryImpl(context, settings, network, accessCodes, server) {
            // No Android foreground-service side effects in a JVM unit test.
            override fun launchService(action: String, address: String?) {}
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a refresh racing a still-starting server cannot leave a Running state with a revoked pin`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        // The engine has not reported itself running yet — the exact window the finding describes.
        whenever(server.isRunning).thenReturn(false)

        val network = GatedNetworkRepository()
        val accessCodes = InMemoryAccessCodeRepository()
        val repo = repo(network, accessCodes)

        val starter = launch { repo.start() }
        // Let start() run up to its suspension inside the gated address() lookup, still holding
        // the mutex.
        runCurrent()

        val refresher = launch { repo.refresh() }
        // refresh() must now be queued behind start()'s lock rather than running concurrently.
        runCurrent()

        network.release()
        starter.join()
        refresher.join()

        // Whatever the final state, a Running state must never carry a pin that AccessCodeRepository
        // has already moved past.
        val finalState = repo.state.value
        if (finalState is ServerState.Running) {
            assertEquals(accessCodes.current, finalState.pin)
        }

        // With start() and refresh() serialized, this scenario is equivalent to running them
        // strictly one after another: start() briefly becomes Running, then refresh() (seeing the
        // engine still not up) revokes the pin and reports Stopped. The buggy, unsynchronized
        // implementation instead published Running with the already-revoked pin here.
        assertEquals(ServerState.Stopped, finalState)
        assertNull(accessCodes.current)
    }
}
