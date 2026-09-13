package ru.kryu.ferryfile.server.auth

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor() {

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val COOLDOWN_MS = 30_000L
    }

    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    fun createSession(): String = UUID.randomUUID().toString().also { validTokens.add(it) }

    fun isValidSession(token: String): Boolean = validTokens.contains(token)

    fun revokeSession(token: String) {
        validTokens.remove(token)
    }

    fun reset() {
        validTokens.clear()
        failedAttempts.clear()
        blockedUntil.clear()
    }

    fun isBlocked(ip: String): Boolean {
        val until = blockedUntil[ip] ?: return false
        return if (System.currentTimeMillis() < until) true
        else { blockedUntil.remove(ip); failedAttempts.remove(ip); false }
    }

    fun recordFailedAttempt(ip: String) {
        val count = failedAttempts.merge(ip, 1, Int::plus) ?: 1
        if (count >= MAX_ATTEMPTS) blockedUntil[ip] = System.currentTimeMillis() + COOLDOWN_MS
    }

    fun resetAttempts(ip: String) {
        failedAttempts.remove(ip)
        blockedUntil.remove(ip)
    }
}
