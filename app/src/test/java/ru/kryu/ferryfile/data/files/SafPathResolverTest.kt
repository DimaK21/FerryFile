package ru.kryu.ferryfile.data.files

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.domain.model.FilePath

class SafPathResolverTest {

    private val rootsProvider: SafRootsProvider = mock()
    private val resolver = SafPathResolver(rootsProvider)

    @Test fun `virtual root resolves to nothing`() {
        assertNull(resolver.resolve(FilePath.ROOT))
    }

    @Test fun `first segment selects the shared folder by index`() {
        val first = dir()
        val second = dir()
        whenever(rootsProvider.roots()).thenReturn(listOf(first, second))
        assertSame(second, resolver.resolve(FilePath.parse("1")!!))
    }

    @Test fun `unknown root index resolves to null`() {
        val onlyRoot = dir()
        whenever(rootsProvider.roots()).thenReturn(listOf(onlyRoot))
        assertNull(resolver.resolve(FilePath.parse("7")!!))
    }

    @Test fun `nested segments are walked in order`() {
        val root = dir()
        val docs = dir()
        val file: DocumentFile = mock()
        whenever(rootsProvider.roots()).thenReturn(listOf(root))
        whenever(root.findFile("docs")).thenReturn(docs)
        whenever(docs.findFile("report.pdf")).thenReturn(file)

        assertSame(file, resolver.resolve(FilePath.parse("0/docs/report.pdf")!!))
    }

    @Test fun `missing segment resolves to null`() {
        val root = dir()
        whenever(rootsProvider.roots()).thenReturn(listOf(root))
        whenever(root.findFile("ghost")).thenReturn(null)
        assertNull(resolver.resolve(FilePath.parse("0/ghost/deeper")!!))
    }

    @Test fun `a broken shared folder does not shift the roots after it`() {
        val second = dir()
        whenever(rootsProvider.roots()).thenReturn(listOf(null, second))

        assertSame(second, resolver.resolve(FilePath.parse("1")!!))
        assertNull(resolver.resolve(FilePath.parse("0")!!))
    }

    private fun dir(): DocumentFile = mock<DocumentFile>().also { whenever(it.isDirectory).thenReturn(true) }
}
