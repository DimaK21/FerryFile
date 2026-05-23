package ru.kryu.ferryfile.server.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.kryu.ferryfile.server.auth.PasswordHasher
import ru.kryu.ferryfile.server.auth.SessionManager

class AuthRoutesTest {
    private val hasher = PasswordHasher()
    private lateinit var sessionManager: SessionManager
    private lateinit var storedHash: String

    @Before fun setUp() {
        sessionManager = SessionManager()
        storedHash = hasher.hash("testpass")
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application { configureAuthRoutes(sessionManager, { storedHash }, hasher) }
        block()
    }

    @Test fun `correct password returns 200`() = withApp {
        val res = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"testpass"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test fun `wrong password returns 401`() = withApp {
        val res = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
