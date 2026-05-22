package ru.kryu.ferryfile.server.auth

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionManagerTest {
    private lateinit var manager: SessionManager

    @Before fun setUp() { manager = SessionManager() }

    @Test fun `createSession returns non-blank token`() {
        assertTrue(manager.createSession().isNotBlank())
    }

    @Test fun `isValidSession true for created token`() {
        assertTrue(manager.isValidSession(manager.createSession()))
    }

    @Test fun `isValidSession false for unknown token`() {
        assertFalse(manager.isValidSession("bogus"))
    }

    @Test fun `reset invalidates all sessions`() {
        val token = manager.createSession()
        manager.reset()
        assertFalse(manager.isValidSession(token))
    }

    @Test fun `not blocked after 2 attempts`() {
        repeat(2) { manager.recordFailedAttempt("1.2.3.4") }
        assertFalse(manager.isBlocked("1.2.3.4"))
    }

    @Test fun `blocked after 3 attempts`() {
        repeat(3) { manager.recordFailedAttempt("1.2.3.5") }
        assertTrue(manager.isBlocked("1.2.3.5"))
    }

    @Test fun `resetAttempts unblocks IP`() {
        repeat(3) { manager.recordFailedAttempt("1.2.3.6") }
        manager.resetAttempts("1.2.3.6")
        assertFalse(manager.isBlocked("1.2.3.6"))
    }
}
