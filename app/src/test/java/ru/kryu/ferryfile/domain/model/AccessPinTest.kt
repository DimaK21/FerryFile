package ru.kryu.ferryfile.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessPinTest {

    @Test fun `six digits are accepted`() {
        assertEquals("012345", AccessPin.parse("012345")!!.digits)
    }

    @Test fun `wrong length is rejected`() {
        assertNull(AccessPin.parse("12345"))
        assertNull(AccessPin.parse("1234567"))
        assertNull(AccessPin.parse(""))
    }

    @Test fun `non digits are rejected`() {
        assertNull(AccessPin.parse("12a456"))
        assertNull(AccessPin.parse(" 12345"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `of throws on invalid pin`() {
        AccessPin.of("nope")
    }
}
