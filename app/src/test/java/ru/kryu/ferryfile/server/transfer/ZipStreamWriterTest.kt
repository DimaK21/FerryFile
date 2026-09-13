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

    @Test fun `unreadable entries are skipped instead of breaking the archive, and are named in a marker entry`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(
            listOf(
                ZipStreamWriter.Entry("ghost.txt") { null },
                ZipStreamWriter.Entry("a.txt") { "alpha".byteInputStream() }
            ),
            out
        )

        val entries = readZipEntries(out)

        assertEquals("alpha", entries["a.txt"])
        assertEquals("ghost.txt", entries["_ferryfile-skipped.txt"])
    }

    @Test fun `a complete archive carries no skipped-files marker`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(listOf(ZipStreamWriter.Entry("a.txt") { "alpha".byteInputStream() }), out)

        assertEquals(setOf("a.txt"), readZipEntries(out).keys)
    }

    @Test fun `duplicate entry names are disambiguated so both survive`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(
            listOf(
                ZipStreamWriter.Entry("a.txt") { "first".byteInputStream() },
                ZipStreamWriter.Entry("a.txt") { "second".byteInputStream() }
            ),
            out
        )

        val entries = readZipEntries(out)

        assertEquals(setOf("a.txt", "a (2).txt"), entries.keys)
        assertEquals("first", entries["a.txt"])
        assertEquals("second", entries["a (2).txt"])
    }

    @Test fun `empty selection produces a valid empty archive`() = runTest {
        val out = ByteArrayOutputStream()
        writer.write(emptyList(), out)
        assertTrue(out.size() > 0)
    }

    private fun readZipEntries(out: ByteArrayOutputStream): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }
}
