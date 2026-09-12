package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.model.TransferEvent
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

    private fun multipart(fileName: String, content: String) = MultiPartFormDataContent(
        formData {
            append(
                "file", content.toByteArray(),
                Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                }
            )
        }
    )

    @Test fun `upload stores the file and answers with a serializable body`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        val token = sessionManager.createSession()

        val res = client.post("/api/upload?path=0%2Fdocs&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello ferry"))
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"files":1,"bytes":11}""", res.bodyAsText())
        assertEquals("hello ferry", storage.writtenFiles["0/docs/notes.txt"]!!.toString(Charsets.UTF_8.name()))
    }

    @Test fun `upload emits a done event`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        val token = sessionManager.createSession()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val done = scope.async(start = CoroutineStart.UNDISPATCHED) {
            transferProgress.events.filterIsInstance<TransferEvent.Done>().first()
        }

        client.post("/api/upload?path=0%2Fdocs&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello ferry"))
        }

        val event = withTimeout(5_000) { done.await() }
        assertEquals("tx-1", event.transferId)
        assertEquals(1, event.files)
        assertEquals(11L, event.bytes)
        scope.cancel()
    }

    @Test fun `upload into the virtual root is rejected with an explanation`() = withApp {
        val token = sessionManager.createSession()

        val res = client.post("/api/upload?path=%2F&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello"))
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"root_not_writable"}""", res.bodyAsText())
    }

    @Test fun `upload with a malformed path is rejected`() = withApp {
        val token = sessionManager.createSession()

        val res = client.post("/api/upload?path=0%2F..%2Fetc&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello"))
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"invalid_path"}""", res.bodyAsText())
    }

    @Test fun `upload without a session is rejected`() = withApp {
        val res = client.post("/api/upload?path=0%2Fdocs&transferId=tx-1") {
            setBody(multipart("notes.txt", "hello"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
