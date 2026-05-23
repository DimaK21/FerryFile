package ru.kryu.ferryfile.server.transfer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UploadHandlerTest {
    private val handler = UploadHandler()

    @Test fun `writes input to output correctly`() {
        val content = "uploaded content"
        val out = ByteArrayOutputStream()
        handler.writeEntry(ByteArrayInputStream(content.toByteArray()), out) {}
        assertEquals(content, out.toString(Charsets.UTF_8.name()))
    }

    @Test fun `progress callback receives final byte count`() {
        val data = ByteArray(2048) { it.toByte() }
        val counts = mutableListOf<Long>()
        handler.writeEntry(data.inputStream(), ByteArrayOutputStream()) { counts += it }
        assertEquals(2048L, counts.last())
    }
}
