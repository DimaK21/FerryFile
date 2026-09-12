package ru.kryu.ferryfile.data.server

import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.model.AccessPin

class InMemoryAccessCodeRepositoryTest {

    private val repo = InMemoryAccessCodeRepository()

    @Test fun `no pin is issued before the server starts`() {
        assertNull(repo.current)
        assertFalse(repo.verify("000000"))
    }

    @Test fun `issued pin has six digits`() {
        val pin = repo.issue()
        assertEquals(AccessPin.LENGTH, pin.digits.length)
        assertTrue(pin.digits.all { it in '0'..'9' })
        assertEquals(pin, repo.current)
    }

    @Test fun `issued pin verifies and a wrong one does not`() {
        val pin = repo.issue()
        assertTrue(repo.verify(pin.digits))
        assertFalse(repo.verify("999999".takeIf { it != pin.digits } ?: "111111"))
    }

    @Test fun `revoked pin stops verifying`() {
        val pin = repo.issue()
        repo.revoke()
        assertNull(repo.current)
        assertFalse(repo.verify(pin.digits))
    }

    @Test fun `reissue replaces the previous pin`() {
        val first = repo.issue()
        repeat(20) { repo.issue() }
        assertNotEquals(first, repo.current)
        assertFalse(repo.verify(first.digits))
    }

    @Test fun `verify tolerates malformed input`() {
        repo.issue()
        assertFalse(repo.verify(""))
        assertFalse(repo.verify("abc"))
        assertFalse(repo.verify("0000000000"))
    }
}
