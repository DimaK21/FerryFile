package ru.kryu.ferryfile.domain.model

import org.junit.Assert.*
import org.junit.Test

class PortTest {

    @Test fun `default port is 8080`() {
        assertEquals(8080, Port.DEFAULT.value)
    }

    @Test fun `port inside range is accepted`() {
        assertEquals(1024, Port.parse(1024)!!.value)
        assertEquals(65535, Port.parse(65535)!!.value)
    }

    @Test fun `port below range is rejected`() {
        assertNull(Port.parse(1023))
        assertNull(Port.parse(0))
        assertNull(Port.parse(-1))
    }

    @Test fun `port above range is rejected`() {
        assertNull(Port.parse(65536))
    }

    @Test fun `text is parsed when numeric and in range`() {
        assertEquals(9000, Port.parse("9000")!!.value)
    }

    @Test fun `non numeric text is rejected`() {
        assertNull(Port.parse("abc"))
        assertNull(Port.parse(""))
    }
}
