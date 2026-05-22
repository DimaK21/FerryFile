package ru.kryu.ferryfile.server.auth

import org.junit.Assert.*
import org.junit.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test fun `hash is not equal to plaintext`() {
        assertNotEquals("secret", hasher.hash("secret"))
    }

    @Test fun `correct password verifies`() {
        val hash = hasher.hash("myPassword")
        assertTrue(hasher.verify("myPassword", hash))
    }

    @Test fun `wrong password does not verify`() {
        val hash = hasher.hash("myPassword")
        assertFalse(hasher.verify("wrong", hash))
    }

    @Test fun `two hashes of same password differ (salt)`() {
        assertNotEquals(hasher.hash("abc"), hasher.hash("abc"))
    }

    @Test fun `malformed stored hash returns false`() {
        assertFalse(hasher.verify("secret", "nocolon"))
        assertFalse(hasher.verify("secret", "oddlength:oddlength"))
    }
}
