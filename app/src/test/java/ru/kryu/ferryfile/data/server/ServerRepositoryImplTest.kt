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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.NetworkRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer
import ru.kryu.ferryfile.service.FileServerService

class ServerRepositoryImplTest {

    private val context: Context = mock()
    private val settings: SettingsRepository = mock()
    private val server: KtorServer = mock()

    private class TestServerRepository(
        context: Context,
        settings: SettingsRepository,
        network: NetworkRepository,
        accessCodes: InMemoryAccessCodeRepository,
        server: KtorServer
    ) : ServerRepositoryImpl(context, settings, network, accessCodes, server) {
        val launches = mutableListOf<LaunchCall>()

        // No Android foreground-service side effects in a JVM unit test.
        override fun launchService(
            action: String,
            address: String?,
            port: Int?,
            host: String?,
            useHttps: Boolean?
        ) {
            launches += LaunchCall(action, address, port, host, useHttps)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a refresh racing a still-starting server cannot leave a Running state with a revoked pin`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(false))
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

    @Test
    fun `TLS preparation failure resets a starting server`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(true))
        whenever(server.isRunning).thenReturn(false)
        whenever(server.prepareTls(anyOrNull())).thenThrow(IllegalStateException("keystore"))

        val accessCodes = InMemoryAccessCodeRepository()
        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf("10.0.0.5")),
            accessCodes = accessCodes
        )

        val failure = runCatching { repo.start() }

        assertTrue(failure.isFailure)
        assertEquals(ServerState.Stopped, repo.state.value)
        assertNull(accessCodes.current)
    }

    @Test
    fun `start forwards HTTPS snapshot when there is no network address`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(true))
        whenever(server.isRunning).thenReturn(false)
        whenever(server.prepareTls(anyOrNull())).thenReturn("fingerprint")

        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf(null)),
            accessCodes = InMemoryAccessCodeRepository()
        )

        repo.start()

        assertEquals(
            LaunchCall(
                action = FileServerService.ACTION_START,
                address = null,
                port = Port.DEFAULT.value,
                host = null,
                useHttps = true
            ),
            repo.launches.single()
        )
    }

    @Test
    fun `start stays Starting until the service confirms that the engine is running`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(false))
        whenever(server.isRunning).thenReturn(false)

        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf("10.0.0.5")),
            accessCodes = InMemoryAccessCodeRepository()
        )

        repo.start()

        assertEquals(ServerState.Starting, repo.state.value)
    }

    @Test
    fun `stop waits for the engine shutdown before publishing stopped`() = runTest {
        whenever(server.stop()).thenReturn(Unit)

        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf()),
            accessCodes = InMemoryAccessCodeRepository()
        )

        repo.stop()

        verify(server).stop()
        assertEquals(ServerState.Stopped, repo.state.value)
        assertEquals(FileServerService.ACTION_STOP, repo.launches.single().action)
    }

    @Test
    fun `HTTPS refresh restarts the server when the network address changes`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(true))
        whenever(server.isRunning).thenReturn(false, true)
        whenever(server.prepareTls(anyOrNull()))
            .thenReturn("old-fingerprint", "new-fingerprint")
        whenever(server.currentTlsHost()).thenReturn("10.0.0.5")

        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf("10.0.0.5", "10.0.0.6")),
            accessCodes = InMemoryAccessCodeRepository()
        )

        repo.start()
        repo.refresh()

        verify(server).stop()
        assertEquals(2, repo.launches.size)
        assertEquals("https://10.0.0.6:8080", repo.launches[1].address)
        assertEquals("10.0.0.6", repo.launches[1].host)
        assertEquals("new-fingerprint", (repo.state.value as ServerState.Running).certificateFingerprint)
    }

    @Test
    fun `failed HTTPS restart also stops the foreground service`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(true))
        whenever(server.isRunning).thenReturn(false, true)
        whenever(server.prepareTls(anyOrNull()))
            .thenReturn("old-fingerprint")
            .thenThrow(IllegalStateException("keystore"))
        whenever(server.currentTlsHost()).thenReturn("10.0.0.5")

        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf("10.0.0.5", "10.0.0.6")),
            accessCodes = InMemoryAccessCodeRepository()
        )

        repo.start()
        val failure = runCatching { repo.refresh() }

        assertTrue(failure.isFailure)
        assertEquals(ServerState.Stopped, repo.state.value)
        assertEquals(FileServerService.ACTION_STOP, repo.launches.last().action)
    }

    @Test
    fun `stop publishes Stopping before the engine shutdown returns`() = runTest {
        whenever(server.isRunning).thenReturn(true)
        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf()),
            accessCodes = InMemoryAccessCodeRepository()
        )
        var stateDuringShutdown: ServerState? = null
        whenever(server.stop()).thenAnswer {
            stateDuringShutdown = repo.state.value
            Unit
        }

        repo.stop()

        assertEquals(ServerState.Stopping, stateDuringShutdown)
        assertEquals(ServerState.Stopped, repo.state.value)
    }

    @Test
    fun `service-origin stop never dispatches ACTION_STOP`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(false))
        whenever(server.isRunning).thenReturn(false, true)
        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf("10.0.0.5", "10.0.0.5")),
            accessCodes = InMemoryAccessCodeRepository()
        )
        repo.start()
        repo.refresh()
        var stateDuringShutdown: ServerState? = null
        whenever(server.stop()).thenAnswer {
            stateDuringShutdown = repo.state.value
            Unit
        }

        repo.stopFromService()

        assertEquals(ServerState.Stopping, stateDuringShutdown)
        assertEquals(ServerState.Stopped, repo.state.value)
        assertTrue(repo.launches.none { it.action == FileServerService.ACTION_STOP })
    }

    @Test
    fun `service-origin stop of an already dead server goes straight to stopped`() = runTest {
        whenever(server.isRunning).thenReturn(false)
        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf()),
            accessCodes = InMemoryAccessCodeRepository()
        )
        var stateDuringShutdown: ServerState? = null
        whenever(server.stop()).thenAnswer {
            stateDuringShutdown = repo.state.value
            Unit
        }

        repo.stopFromService()

        assertEquals(ServerState.Stopped, repo.state.value)
        assertNotSame(ServerState.Stopping, stateDuringShutdown)
    }

    @Test
    fun `second start while starting is ignored`() = runTest {
        whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
        whenever(settings.useHttps).thenReturn(MutableStateFlow(false))
        whenever(server.isRunning).thenReturn(false)
        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf("10.0.0.5")),
            accessCodes = InMemoryAccessCodeRepository()
        )

        repo.start()
        repo.start()

        assertEquals(1, repo.launches.size)
        assertEquals(ServerState.Starting, repo.state.value)
    }

    @Test
    fun `stop publishes stopped even when the engine shutdown throws`() = runTest {
        whenever(server.isRunning).thenReturn(true)
        whenever(server.stop()).thenThrow(IllegalStateException("shutdown"))
        val accessCodes = InMemoryAccessCodeRepository()
        val repo = repo(
            network = SequenceNetworkRepository(mutableListOf()),
            accessCodes = accessCodes
        )

        val failure = runCatching { repo.stop() }

        assertTrue(failure.isFailure)
        assertEquals(ServerState.Stopped, repo.state.value)
        assertNull(accessCodes.current)
    }

    private fun repo(network: NetworkRepository, accessCodes: InMemoryAccessCodeRepository) =
        TestServerRepository(context, settings, network, accessCodes, server)
}

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

private class SequenceNetworkRepository(
    private val addresses: MutableList<String?>
) : NetworkRepository {
    override suspend fun localAddress(): String? = addresses.removeAt(0)
}

private data class LaunchCall(
    val action: String,
    val address: String?,
    val port: Int?,
    val host: String?,
    val useHttps: Boolean?
)
