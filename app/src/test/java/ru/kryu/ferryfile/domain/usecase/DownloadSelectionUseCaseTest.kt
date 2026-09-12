package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.model.FilePath

class DownloadSelectionUseCaseTest {

    private val storage = FakeFileStorageRepository()
    private val useCase = DownloadSelectionUseCase(storage)

    private fun path(raw: String) = FilePath.parse(raw)!!

    @Before fun setUp() {
        storage.addDirectory("0")
        storage.addFile("0/report.pdf", "pdf-bytes")
        storage.addFile("0/photo.jpg", "jpg-bytes")
        storage.addDirectory("0/docs")
        storage.addFile("0/docs/a.txt", "a")
        storage.addDirectory("0/docs/sub")
        storage.addFile("0/docs/sub/b.txt", "b")
    }

    @Test fun `single file is served directly without an archive`() = runTest {
        val selection = useCase.resolve(listOf(path("0/report.pdf")))

        assertTrue(selection is DownloadSelectionUseCase.Selection.SingleFile)
        assertEquals("report.pdf", (selection as DownloadSelectionUseCase.Selection.SingleFile).node.name)
    }

    @Test fun `single folder becomes an archive named after the folder`() = runTest {
        val selection = useCase.resolve(listOf(path("0/docs")))

        selection as DownloadSelectionUseCase.Selection.Archive
        assertEquals("docs.zip", selection.fileName)
        assertEquals(
            listOf("docs/a.txt", "docs/sub/b.txt"),
            selection.entries.map { it.entryName }.sorted()
        )
    }

    @Test fun `multiple paths become a selection archive`() = runTest {
        val selection = useCase.resolve(listOf(path("0/report.pdf"), path("0/photo.jpg")))

        selection as DownloadSelectionUseCase.Selection.Archive
        assertEquals("ferryfile-selection.zip", selection.fileName)
        assertEquals(listOf("report.pdf", "photo.jpg"), selection.entries.map { it.entryName })
    }

    @Test fun `mixed selection keeps folder structure inside the archive`() = runTest {
        val selection = useCase.resolve(listOf(path("0/report.pdf"), path("0/docs")))

        selection as DownloadSelectionUseCase.Selection.Archive
        assertEquals("ferryfile-selection.zip", selection.fileName)
        assertEquals(
            listOf("docs/a.txt", "docs/sub/b.txt", "report.pdf"),
            selection.entries.map { it.entryName }.sorted()
        )
    }

    @Test fun `unknown path yields NotFound`() = runTest {
        assertEquals(
            DownloadSelectionUseCase.Selection.NotFound,
            useCase.resolve(listOf(path("0/ghost")))
        )
    }

    @Test fun `empty selection yields NotFound`() = runTest {
        assertEquals(DownloadSelectionUseCase.Selection.NotFound, useCase.resolve(emptyList()))
    }

    @Test fun `virtual root cannot be downloaded`() = runTest {
        assertEquals(
            DownloadSelectionUseCase.Selection.NotFound,
            useCase.resolve(listOf(FilePath.ROOT))
        )
    }

    @Test fun `open returns the file content`() = runTest {
        assertEquals("pdf-bytes", useCase.open(path("0/report.pdf"))!!.readBytes().toString(Charsets.UTF_8))
    }
}
