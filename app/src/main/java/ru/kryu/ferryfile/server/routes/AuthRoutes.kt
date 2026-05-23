package ru.kryu.ferryfile.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import ru.kryu.ferryfile.server.auth.PasswordHasher
import ru.kryu.ferryfile.server.auth.SessionManager

@Serializable
data class UserSession(val token: String)

@Serializable
private data class LoginRequest(val password: String)

fun Application.configureAuthRoutes(
    sessionManager: SessionManager,
    passwordHash: () -> String,
    hasher: PasswordHasher
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
            val req = runCatching { call.receive<LoginRequest>() }.getOrNull()
                ?: run { call.respond(HttpStatusCode.BadRequest); return@post }

            if (hasher.verify(req.password, passwordHash())) {
                sessionManager.resetAttempts(ip)
                call.sessions.set(UserSession(sessionManager.createSession()))
                call.respond(HttpStatusCode.OK)
            } else {
                sessionManager.recordFailedAttempt(ip)
                call.respond(HttpStatusCode.Unauthorized, "Invalid password")
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
    install(Sessions) {
        cookie<UserSession>("FERRYFILE_SESSION") { cookie.path = "/" }
    }
    install(Authentication) {
        session<UserSession>("session") {
            validate { if (sessionManager.isValidSession(it.token)) it else null }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }
}
