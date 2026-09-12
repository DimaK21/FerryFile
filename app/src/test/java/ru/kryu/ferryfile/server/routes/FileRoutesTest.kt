package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.usecase.DownloadSelectionUseCase
import ru.kryu.ferryfile.domain.usecase.ListDirectoryUseCase
import ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase
import ru.kryu.ferryfile.server.auth.SessionManager
import ru.kryu.ferryfile.server.transfer.DownloadHandler
import ru.kryu.ferryfile.server.transfer.TransferProgress

class FileRoutesTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var transferProgress: TransferProgress
    private lateinit var storage: FakeFileStorageRepository
    private val assets: AssetManager = mock()

    @Before fun setUp() {
        sessionManager = SessionManager()
        transferProgress = TransferProgress()
        storage = FakeFileStorageRepository()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application {
            configureAuthForTest(sessionManager)
            configureFileRoutes(
                ListDirectoryUseCase(storage),
                DownloadSelectionUseCase(storage),
                SaveUploadUseCase(storage),
                transferProgress,
                DownloadHandler(),
                assets
            )
        }
        block()
    }

    @Test fun `GET api-list without session returns 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/list?path=/").status)
    }

    @Test fun `GET api-list returns the shared folders at the root`() = withApp {
        storage.addDirectory("0")
        val token = sessionManager.createSession()

        val res = client.get("/api/list?path=%2F") { cookie("FERRYFILE_SESSION", token) }

        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test fun `GET api-list rejects a traversal path`() = withApp {
        val token = sessionManager.createSession()
        val res = client.get("/api/list?path=0%2F..%2Fetc") { cookie("FERRYFILE_SESSION", token) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
