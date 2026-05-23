package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DownloadHandlerTest {
    private val handler = DownloadHandler()

    @Test fun `zip contains all entries`() = runTest {
        val entries = listOf(
            DownloadHandler.Entry("a.txt", 5L) { ByteArrayInputStream("hello".toByteArray()) },
            DownloadHandler.Entry("b.txt", 5L) { ByteArrayInputStream("world".toByteArray()) }
        )
        val out = ByteArrayOutputStream()
        handler.streamZip(entries, out) {}
        val zip = ZipInputStream(out.toByteArray().inputStream())
        val names = generateSequence { zip.nextEntry }.map { it.name }.toList()
        assertEquals(listOf("a.txt", "b.txt"), names)
    }

    @Test fun `entry content matches source`() = runTest {
        val content = "test content"
        val entries = listOf(DownloadHandler.Entry("f.txt", content.length.toLong()) {
            ByteArrayInputStream(content.toByteArray())
        })
        val out = ByteArrayOutputStream()
        handler.streamZip(entries, out) {}
        val zip = ZipInputStream(out.toByteArray().inputStream())
        zip.nextEntry
        assertEquals(content, zip.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun `progress callback fires`() = runTest {
        val entries = listOf(DownloadHandler.Entry("d.bin", 1024L) { ByteArray(1024).inputStream() })
        val out = ByteArrayOutputStream()
        val counts = mutableListOf<Long>()
        handler.streamZip(entries, out) { counts += it }
        assertTrue(counts.isNotEmpty())
    }
}
