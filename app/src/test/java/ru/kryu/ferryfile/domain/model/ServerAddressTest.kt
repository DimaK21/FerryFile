package ru.kryu.ferryfile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `address uses HTTP by default`() {
        assertEquals("http://192.168.1.5:8080", ServerAddress("192.168.1.5", Port.DEFAULT).asUrl())
    }

    @Test
    fun `address uses HTTPS when enabled`() {
        assertEquals("https://192.168.1.5:8080", ServerAddress("192.168.1.5", Port.DEFAULT, true).asUrl())
    }

    @Test
    fun `IPv6 address is enclosed in brackets`() {
        assertEquals("http://[fe80::5]:8080", ServerAddress("fe80::5", Port.DEFAULT).asUrl())
    }
}
