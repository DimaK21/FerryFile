package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.InputStream
import javax.inject.Inject

/** Сохраняет один загруженный файл в расшаренную папку, сообщая накопленный объём. */
class SaveUploadUseCase @Inject constructor(
    private val storage: FileStorageRepository
) {

    sealed interface Result {
        data class Saved(val path: FilePath, val bytesWritten: Long) : Result

        /** Корень — виртуальный список расшаренных папок, писать в него некуда. */
        data object RootNotWritable : Result

        data object Failed : Result
    }

    suspend operator fun invoke(
        dir: FilePath,
        fileName: String,
        mimeType: String,
        source: InputStream,
        onBytesWritten: (Long) -> Unit
    ): Result {
        if (dir.isRoot) return Result.RootNotWritable

        val created = storage.createFile(dir, safeName(fileName), mimeType) ?: return Result.Failed
        val sink = storage.write(created) ?: return Result.Failed

        var total = 0L
        sink.use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                total += read
                onBytesWritten(total)
            }
            out.flush()
        }
        return Result.Saved(created, total)
    }

    /** Имя приходит от клиента, поэтому от него остаётся только последний сегмент. */
    private fun safeName(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: FALLBACK_NAME

    private companion object {
        const val FALLBACK_NAME = "upload"
    }
}
