package ru.kryu.ferryfile.data.files

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.domain.model.FilePath

class SafFileStorageRepositoryTest {

    private val context: Context = mock()
    private val rootsProvider: SafRootsProvider = mock()
    private val resolver: SafPathResolver = mock()
    private val repo = SafFileStorageRepository(context, rootsProvider, resolver)

    @Test fun `listRoot maps shared folders to indexed paths`() = runTest {
        val photos = folder("Photos")
        val docs = folder("Docs")
        whenever(rootsProvider.roots()).thenReturn(listOf(photos, docs))

        val nodes = repo.listRoot()

        assertEquals(listOf("0", "1"), nodes.map { it.path.raw })
        assertEquals(listOf("Photos", "Docs"), nodes.map { it.name })
        assertTrue(nodes.all { it.isDirectory })
    }

    @Test fun `listRoot keeps the original index of a surviving root after a broken one`() = runTest {
        val docs = folder("Docs")
        whenever(rootsProvider.roots()).thenReturn(listOf(null, docs))

        val nodes = repo.listRoot()

        assertEquals(listOf("1"), nodes.map { it.path.raw })
    }

    @Test fun `list maps children to nested paths`() = runTest {
        val dir = folder("docs")
        val report = file("report.pdf", 42L)
        val sub = folder("sub")
        whenever(dir.listFiles()).thenReturn(arrayOf(report, sub))
        whenever(resolver.resolve(FilePath.parse("0/docs")!!)).thenReturn(dir)

        val nodes = repo.list(FilePath.parse("0/docs")!!)!!

        assertEquals(listOf("0/docs/report.pdf", "0/docs/sub"), nodes.map { it.path.raw })
        assertEquals(42L, nodes.first().sizeBytes)
        assertEquals("text/plain", nodes.first().mimeType)
    }

    @Test fun `list returns null for a file`() = runTest {
        val report = file("report.pdf", 1L)
        whenever(resolver.resolve(any())).thenReturn(report)
        assertNull(repo.list(FilePath.parse("0/report.pdf")!!))
    }

    @Test fun `list returns null for unknown path`() = runTest {
        whenever(resolver.resolve(any())).thenReturn(null)
        assertNull(repo.list(FilePath.parse("0/ghost")!!))
    }

    @Test fun `node describes a single file`() = runTest {
        val report = file("report.pdf", 7L, "application/pdf")
        whenever(resolver.resolve(any())).thenReturn(report)

        val node = repo.node(FilePath.parse("0/report.pdf")!!)!!

        assertEquals("report.pdf", node.name)
        assertEquals(7L, node.sizeBytes)
        assertEquals("application/pdf", node.mimeType)
        assertFalse(node.isDirectory)
    }

    @Test fun `createFile returns the path actually created by the provider`() = runTest {
        val dir = folder("docs")
        val createdDoc = file("notes (1).txt", 0L)
        whenever(resolver.resolve(FilePath.parse("0/docs")!!)).thenReturn(dir)
        whenever(dir.createFile("text/plain", "notes.txt")).thenReturn(createdDoc)

        val created = repo.createFile(FilePath.parse("0/docs")!!, "notes.txt", "text/plain")

        assertEquals("0/docs/notes (1).txt", created!!.raw)
    }

    @Test fun `createFile returns null when destination is not a directory`() = runTest {
        val report = file("report.pdf", 1L)
        whenever(resolver.resolve(any())).thenReturn(report)
        assertNull(repo.createFile(FilePath.parse("0/report.pdf")!!, "notes.txt", "text/plain"))
    }

    private fun folder(name: String): DocumentFile = mock<DocumentFile>().also {
        whenever(it.isDirectory).thenReturn(true)
        whenever(it.name).thenReturn(name)
        whenever(it.lastModified()).thenReturn(1000L)
    }

    private fun file(name: String, size: Long, mime: String = "text/plain"): DocumentFile =
        mock<DocumentFile>().also {
            whenever(it.isDirectory).thenReturn(false)
            whenever(it.name).thenReturn(name)
            whenever(it.length()).thenReturn(size)
            whenever(it.lastModified()).thenReturn(2000L)
            whenever(it.type).thenReturn(mime)
        }
}
