package ru.kryu.ferryfile.server.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.kryu.ferryfile.data.server.InMemoryAccessCodeRepository
import ru.kryu.ferryfile.domain.usecase.VerifyAccessCodeUseCase
import ru.kryu.ferryfile.server.auth.SessionManager

class AuthRoutesTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var accessCodes: InMemoryAccessCodeRepository
    private lateinit var pin: String

    @Before fun setUp() {
        sessionManager = SessionManager()
        accessCodes = InMemoryAccessCodeRepository()
        pin = accessCodes.issue().digits
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application {
            configureAuthRoutes(sessionManager, VerifyAccessCodeUseCase(accessCodes))
            // Test-only protected endpoint so tests can prove a session cookie does (or no
            // longer does) authenticate, without depending on any real protected route.
            routing {
                authenticate("session") {
                    get("/protected") { call.respond(HttpStatusCode.OK) }
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(candidate: String) =
        client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"$candidate"}""")
        }

    @Test fun `correct pin returns 200 and sets a session cookie`() = withApp {
        val res = login(pin)
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.SetCookie]!!.contains("FERRYFILE_SESSION"))
    }

    @Test fun `wrong pin returns 401`() = withApp {
        val wrong = if (pin == "000000") "111111" else "000000"
        assertEquals(HttpStatusCode.Unauthorized, login(wrong).status)
    }

    @Test fun `fourth failed attempt is rate limited`() = withApp {
        val wrong = if (pin == "000000") "111111" else "000000"
        repeat(3) { login(wrong) }
        assertEquals(HttpStatusCode.TooManyRequests, login(wrong).status)
    }

    @Test fun `revoked pin stops being accepted`() = withApp {
        accessCodes.revoke()
        assertEquals(HttpStatusCode.Unauthorized, login(pin).status)
    }

    @Test fun `malformed body returns 400`() = withApp {
        val res = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"secret"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test fun `logout with no session present still returns 200`() = withApp {
        val res = client.post("/logout")
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test fun `logout revokes the session so the replayed cookie is rejected`() = withApp {
        val loginRes = login(pin)
        val sessionCookie = parseServerSetCookieHeader(loginRes.headers[HttpHeaders.SetCookie]!!).value

        // Sanity check: the freshly issued cookie does authenticate.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/protected") { cookie("FERRYFILE_SESSION", sessionCookie) }.status
        )

        val logoutRes = client.post("/logout") { cookie("FERRYFILE_SESSION", sessionCookie) }
        assertEquals(HttpStatusCode.OK, logoutRes.status)

        // The bug this guards against: /logout only cleared the client-side cookie, leaving
        // the token valid server-side, so replaying the same cookie value by hand (any HTTP
        // client can do this — no browser required) still authenticated.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/protected") { cookie("FERRYFILE_SESSION", sessionCookie) }.status
        )

        // Logging out a second time with the now-revoked cookie must still be a graceful 200.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/logout") { cookie("FERRYFILE_SESSION", sessionCookie) }.status
        )
    }
}
