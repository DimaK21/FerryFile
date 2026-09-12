package ru.kryu.ferryfile.domain.model

import org.junit.Assert.*
import org.junit.Test

class FilePathTest {

    @Test fun `slash parses to root`() {
        val path = FilePath.parse("/")
        assertNotNull(path)
        assertTrue(path!!.isRoot)
        assertEquals(emptyList<String>(), path.segments)
    }

    @Test fun `root index only is a valid path`() {
        val path = FilePath.parse("0")!!
        assertFalse(path.isRoot)
        assertEquals(0, path.rootIndex)
        assertEquals(listOf("0"), path.segments)
        assertEquals("0", path.name)
    }

    @Test fun `nested path keeps segments and name`() {
        val path = FilePath.parse("1/docs/report.pdf")!!
        assertEquals(1, path.rootIndex)
        assertEquals(listOf("1", "docs", "report.pdf"), path.segments)
        assertEquals("report.pdf", path.name)
        assertEquals("1/docs/report.pdf", path.raw)
    }

    @Test fun `leading and trailing slashes are normalized away`() {
        assertEquals("0/docs", FilePath.parse("/0/docs/")!!.raw)
    }

    @Test fun `parent traversal is rejected`() {
        assertNull(FilePath.parse("0/../secret"))
        assertNull(FilePath.parse("0/.."))
    }

    @Test fun `current directory segment is rejected`() {
        assertNull(FilePath.parse("0/./docs"))
    }

    @Test fun `empty segment is rejected`() {
        assertNull(FilePath.parse("0//docs"))
    }

    @Test fun `non numeric root is rejected`() {
        assertNull(FilePath.parse("docs/report.pdf"))
    }

    @Test fun `negative root index is rejected`() {
        assertNull(FilePath.parse("-1/docs"))
    }

    @Test fun `null and blank are rejected`() {
        assertNull(FilePath.parse(null))
        assertNull(FilePath.parse(""))
        assertNull(FilePath.parse("   "))
    }

    @Test fun `root factory builds path for index`() {
        assertEquals("2", FilePath.root(2).raw)
    }

    @Test fun `child appends a segment`() {
        assertEquals("0/docs/a.txt", FilePath.parse("0/docs")!!.child("a.txt")!!.raw)
    }

    @Test fun `child rejects traversal and separators`() {
        val dir = FilePath.parse("0/docs")!!
        assertNull(dir.child(".."))
        assertNull(dir.child("."))
        assertNull(dir.child(""))
        assertNull(dir.child("sub/file.txt"))
    }

    @Test fun `root has no children`() {
        assertNull(FilePath.ROOT.child("0"))
    }
}
