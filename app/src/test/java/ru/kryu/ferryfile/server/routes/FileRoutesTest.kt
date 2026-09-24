package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
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
import ru.kryu.ferryfile.server.transfer.TransferProgress
import ru.kryu.ferryfile.server.transfer.ZipStreamWriter

class FileRoutesTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var transferProgress: TransferProgress
    private lateinit var storage: FakeFileStorageRepository
    private val assets: AssetManager = mock()

    /**
     * Captures what the upload route's `logError` seam would otherwise send to
     * `android.util.Log` — which is a stub that throws unless mocked under the local JVM unit
     * test runtime. Passing this instead of relying on `configureFileRoutes`'s default keeps
     * this whole suite independent of that framework stub (no `testOptions.unitTests` flag
     * needed) and, incidentally, lets a test assert a failure was actually logged.
     */
    private val loggedErrors = mutableListOf<Pair<String, Throwable>>()

    @Before fun setUp() {
        sessionManager = SessionManager()
        transferProgress = TransferProgress()
        storage = FakeFileStorageRepository()
        loggedErrors.clear()
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

    @Test fun `single file is streamed as is, not zipped`() = withApp {
        storage.addDirectory("0")
        storage.addFile("0/report.pdf", "pdf-bytes", "application/pdf")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Freport.pdf") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("pdf-bytes", res.bodyAsText())
        assertEquals("application/pdf", res.contentType()?.withoutParameters()?.toString())
        val disposition = res.headers[HttpHeaders.ContentDisposition]!!
        assertTrue(disposition.contains("""filename="report.pdf""""))
        assertFalse(disposition.contains(".zip"))
        assertEquals("9", res.headers[HttpHeaders.ContentLength])
        assertNull(res.headers[HttpHeaders.TransferEncoding])
    }

    @Test fun `folder is streamed as a zip named after the folder`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        storage.addFile("0/docs/a.txt", "alpha")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Fdocs") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.ContentDisposition]!!.contains("""filename="docs.zip""""))
        assertEquals(listOf("docs/a.txt"), zipEntryNames(res.readRawBytes()))
    }

    @Test fun `multiple paths are packed into one selection archive`() = withApp {
        storage.addDirectory("0")
        storage.addFile("0/a.txt", "alpha")
        storage.addFile("0/b.txt", "beta")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Fa.txt&path=0%2Fb.txt") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertTrue(res.headers[HttpHeaders.ContentDisposition]!!.contains("ferryfile-selection.zip"))
        assertEquals(listOf("a.txt", "b.txt"), zipEntryNames(res.readRawBytes()).sorted())
    }

    @Test fun `unknown path returns 404`() = withApp {
        storage.addDirectory("0")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Fghost") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test fun `download without a path is rejected`() = withApp {
        val token = sessionManager.createSession()
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/download") { cookie("FERRYFILE_SESSION", token) }.status
        )
    }

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

    @Test fun `upload larger than Ktor's default multipart part limit still completes`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        val token = sessionManager.createSession()

        // Ktor 3's receiveMultipart() defaults to a 52_428_800-byte (50 MiB) formFieldLimit
        // that applies to every multipart part, file parts included — this is the exact
        // ceiling that silently stalled a real 75 MiB upload on-device (see
        // FALLBACK_MULTIPART_PART_LIMIT_BYTES's comment in FileRoutes.kt). A few MiB past that
        // default is enough to prove the route no longer caps file parts there; going only a
        // little over keeps the test fast while still crossing the real boundary that broke.
        // Sent with no per-part Content-Length (rawMultipartFileBody) but a correct overall
        // one (a plain ByteArray body declares its own size), matching a real curl -F /
        // browser upload and this route's own Content-Length-bound formFieldLimit — so it is
        // allowed its full declared size and hits the actual `readUntil` streaming code path.
        val boundary = "ferryfileBigUploadBoundary"
        val size = 52_428_800 + 2_000_000
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val body = rawMultipartFileBody(boundary, "big.bin", "application/octet-stream", bytes)

        val res = withTimeout(30_000) {
            client.post("/api/upload?path=0%2Fdocs&transferId=tx-big") {
                cookie("FERRYFILE_SESSION", token)
                contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
                setBody(body)
            }
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"files":1,"bytes":$size}""", res.bodyAsText())
        val written = storage.writtenFiles["0/docs/big.bin"]!!.toByteArray()
        assertEquals(size, written.size)
        assertArrayEquals(bytes, written)
    }

    @Test fun `a part's bogus declared length ends the request in a response, not a hang`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        val token = sessionManager.createSession()

        // A part whose own Content-Length wildly exceeds the whole request's declared size
        // (which this route now uses as its multipart formFieldLimit) makes Ktor's CIO parser
        // throw synchronously, inside its own coroutine, before any of this part's body is
        // read — precisely the failure shape that used to deadlock the connection: the parser
        // dies while the raw request bytes are still arriving. This is what the raw-channel
        // drain in FileRoutes.kt's catch block exists for; this test proves the request always
        // ends in a definite response instead of hanging.
        val boundary = "ferryfileMalformedBoundary"
        val body = rawMultipartFileBody(
            boundary, "bad.bin", "application/octet-stream",
            content = "short body, nowhere near the declared length".toByteArray(Charsets.UTF_8),
            extraPartHeaders = "Content-Length: 999999999\r\n"
        )

        val res = withTimeout(5_000) {
            client.post("/api/upload?path=0%2Fdocs&transferId=tx-malformed") {
                cookie("FERRYFILE_SESSION", token)
                contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
                setBody(body)
            }
        }

        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("""{"error":"upload_failed"}""", res.bodyAsText())
    }

    @Test fun `upload failure responds with an error and emits a TransferEvent Error`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        storage.createFileFails = true
        val token = sessionManager.createSession()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val errorEvent = scope.async(start = CoroutineStart.UNDISPATCHED) {
            transferProgress.events.filterIsInstance<TransferEvent.Error>().first()
        }

        val res = withTimeout(5_000) {
            client.post("/api/upload?path=0%2Fdocs&transferId=tx-fail") {
                cookie("FERRYFILE_SESSION", token)
                setBody(multipart("notes.txt", "hello ferry"))
            }
        }

        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("""{"error":"upload_failed"}""", res.bodyAsText())

        val event = withTimeout(5_000) { errorEvent.await() }
        assertEquals("tx-fail", event.transferId)
        assertEquals("upload_failed", event.code)
        scope.cancel()

        assertTrue(
            "expected the failure to be logged via the logError seam",
            loggedErrors.any { (message, _) -> message.contains("tx-fail") }
        )
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

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application {
            configureAuthForTest(sessionManager)
            configureFileRoutes(
                ListDirectoryUseCase(storage),
                DownloadSelectionUseCase(storage),
                SaveUploadUseCase(storage),
                transferProgress,
                ZipStreamWriter(),
                assets,
                logError = { message, cause -> loggedErrors += message to cause }
            )
        }
        block()
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
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

    /**
     * Builds a raw multipart/form-data body by hand, deliberately *not* via
     * `MultiPartFormDataContent`'s `ByteArray`/`ChannelProvider` helpers: a `ByteArray` part
     * auto-attaches a per-part `Content-Length` header (steering the server into
     * `parsePartBodyImpl`'s declared-length fast path, not the `readUntil` streaming path that
     * actually broke), and a `ChannelProvider(size = null)` part drops the *overall* request's
     * Content-Length along with it — which this route's own fix depends on to size its
     * multipart limit. A hand-built `ByteArray` body gives an exact overall Content-Length
     * (`setBody(ByteArray)` always declares its own size) with no per-part Content-Length line
     * unless [extraPartHeaders] adds one — exactly what curl `-F` and a browser's
     * `fetch(FormData)` actually send on the wire, and exactly the combination that exercises
     * the streaming branch.
     */
    private fun rawMultipartFileBody(
        boundary: String,
        fileName: String,
        contentType: String,
        content: ByteArray,
        extraPartHeaders: String = ""
    ): ByteArray {
        val preamble = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: $contentType\r\n" +
                extraPartHeaders +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
        val epilogue = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return preamble + content + epilogue
    }
}
