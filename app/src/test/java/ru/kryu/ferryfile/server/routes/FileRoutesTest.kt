package ru.kryu.ferryfile.server.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.server.auth.SessionManager
import ru.kryu.ferryfile.server.saf.SafFileProvider
import ru.kryu.ferryfile.server.transfer.DownloadHandler
import ru.kryu.ferryfile.server.transfer.TransferProgress
import ru.kryu.ferryfile.server.transfer.UploadHandler

class FileRoutesTest {
    private lateinit var sessionManager: SessionManager
    private val safFileProvider: SafFileProvider = mock()
    private lateinit var transferProgress: TransferProgress
    private val downloadHandler = DownloadHandler()
    private val uploadHandler = UploadHandler()

    @Before fun setUp() {
        sessionManager = SessionManager()
        transferProgress = TransferProgress()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application {
            configureAuthForTest(sessionManager)
            configureFileRoutes(safFileProvider, transferProgress, downloadHandler, uploadHandler)
        }
        block()
    }

    @Test fun `GET api-list without session returns 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/list?path=/").status)
    }

    @Test fun `GET api-list with valid session returns 200`() = withApp {
        val token = sessionManager.createSession()
        whenever(safFileProvider.listRoot()).thenReturn(emptyList())
        whenever(safFileProvider.isValidPath("/")).thenReturn(true)
        val res = client.get("/api/list?path=/") {
            cookie("FERRYFILE_SESSION", token)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }
}
