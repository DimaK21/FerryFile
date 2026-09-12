package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.model.FilePath

class SaveUploadUseCaseTest {

    private val storage = FakeFileStorageRepository()
    private val useCase = SaveUploadUseCase(storage)

    private fun docs(): FilePath {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        return FilePath.parse("0/docs")!!
    }

    @Test fun `writes the uploaded bytes into the destination folder`() = runTest {
        val dir = docs()
        val payload = "hello ferry"

        val result = useCase(dir, "notes.txt", "text/plain", payload.byteInputStream()) {}

        assertTrue(result is SaveUploadUseCase.Result.Saved)
        result as SaveUploadUseCase.Result.Saved
        assertEquals("0/docs/notes.txt", result.path.raw)
        assertEquals(payload.length.toLong(), result.bytesWritten)
        assertEquals(payload, storage.writtenFiles["0/docs/notes.txt"]!!.toString(Charsets.UTF_8.name()))
    }

    @Test fun `reports cumulative progress while copying`() = runTest {
        val dir = docs()
        val payload = ByteArray(20_000) { 'x'.code.toByte() }
        val reported = mutableListOf<Long>()

        useCase(dir, "big.bin", "application/octet-stream", payload.inputStream()) { reported += it }

        assertTrue(reported.isNotEmpty())
        assertEquals(20_000L, reported.last())
        assertEquals(reported.sorted(), reported)
    }

    @Test fun `refuses to write into the virtual root`() = runTest {
        val result = useCase(FilePath.ROOT, "notes.txt", "text/plain", "x".byteInputStream()) {}
        assertEquals(SaveUploadUseCase.Result.RootNotWritable, result)
        assertTrue(storage.writtenFiles.isEmpty())
    }

    @Test fun `fails when the file cannot be created`() = runTest {
        val dir = docs()
        storage.createFileFails = true

        val result = useCase(dir, "notes.txt", "text/plain", "x".byteInputStream()) {}

        assertEquals(SaveUploadUseCase.Result.Failed, result)
    }

    @Test fun `strips directory components from the client supplied name`() = runTest {
        val dir = docs()

        val result = useCase(dir, "../../etc/passwd", "text/plain", "x".byteInputStream()) {}

        assertTrue(result is SaveUploadUseCase.Result.Saved)
        assertEquals("0/docs/passwd", (result as SaveUploadUseCase.Result.Saved).path.raw)
    }

    @Test fun `falls back to a safe name when the client sends only separators`() = runTest {
        val dir = docs()

        val result = useCase(dir, "..", "text/plain", "x".byteInputStream()) {}

        assertEquals("0/docs/upload", (result as SaveUploadUseCase.Result.Saved).path.raw)
    }
}
