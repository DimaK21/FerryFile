package ru.kryu.ferryfile.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import ru.kryu.ferryfile.domain.usecase.VerifyAccessCodeUseCase
import ru.kryu.ferryfile.server.auth.SessionManager

@Serializable
data class UserSession(val token: String)

@Serializable
private data class LoginRequest(val pin: String)

fun Application.configureAuthRoutes(
    sessionManager: SessionManager,
    verifyAccessCode: VerifyAccessCodeUseCase
) {
    install(Sessions) {
        cookie<UserSession>("FERRYFILE_SESSION") {
            cookie.httpOnly = true
            cookie.path = "/"
        }
    }

    install(Authentication) {
        session<UserSession>("session") {
            validate { if (sessionManager.isValidSession(it.token)) it else null }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }

    routing {
        post("/login") {
            val ip = call.request.local.remoteAddress
            if (sessionManager.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, "Too many attempts. Wait 30 seconds.")
                return@post
            }
            val request = runCatching { call.receive<LoginRequest>() }.getOrNull()
                ?: run { call.respond(HttpStatusCode.BadRequest); return@post }

            if (verifyAccessCode(request.pin)) {
                sessionManager.resetAttempts(ip)
                call.sessions.set(UserSession(sessionManager.createSession()))
                call.respond(HttpStatusCode.OK)
            } else {
                sessionManager.recordFailedAttempt(ip)
                call.respond(HttpStatusCode.Unauthorized, "Invalid PIN")
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK)
        }
    }
}

// Test helper — not called in production
fun Application.configureAuthForTest(sessionManager: SessionManager) {
    // Custom serializer so tests can set FERRYFILE_SESSION cookie to a raw token string
    val rawTokenSerializer = object : SessionSerializer<UserSession> {
        override fun deserialize(text: String): UserSession = UserSession(text)
        override fun serialize(session: UserSession): String = session.token
    }
    install(Sessions) {
        cookie<UserSession>("FERRYFILE_SESSION") {
            serializer = rawTokenSerializer
            cookie.path = "/"
        }
    }
    install(Authentication) {
        session<UserSession>("session") {
            validate { if (sessionManager.isValidSession(it.token)) it else null }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }
}
