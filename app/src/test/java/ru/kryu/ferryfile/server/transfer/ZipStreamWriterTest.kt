package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ZipStreamWriterTest {

    private val writer = ZipStreamWriter()

    @Test fun `writes every entry with its path preserved`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(
            listOf(
                ZipStreamWriter.Entry("docs/a.txt") { "alpha".byteInputStream() },
                ZipStreamWriter.Entry("docs/sub/b.txt") { "beta".byteInputStream() }
            ),
            out
        )

        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertEquals(mapOf("docs/a.txt" to "alpha", "docs/sub/b.txt" to "beta"), entries)
    }

    @Test fun `unreadable entries are skipped instead of breaking the archive`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(
            listOf(
                ZipStreamWriter.Entry("ghost.txt") { null },
                ZipStreamWriter.Entry("a.txt") { "alpha".byteInputStream() }
            ),
            out
        )

        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }

        assertEquals(listOf("a.txt"), names)
    }

    @Test fun `empty selection produces a valid empty archive`() = runTest {
        val out = ByteArrayOutputStream()
        writer.write(emptyList(), out)
        assertTrue(out.size() > 0)
    }
}
