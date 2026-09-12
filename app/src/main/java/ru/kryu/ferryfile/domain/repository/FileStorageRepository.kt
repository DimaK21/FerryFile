package ru.kryu.ferryfile.domain.repository

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import java.io.InputStream
import java.io.OutputStream

interface FileStorageRepository {

    /** Расшаренные папки как список узлов верхнего уровня. */
    suspend fun listRoot(): List<FileNode>

    /** Содержимое директории; `null`, если путь не найден или это не директория. */
    suspend fun list(path: FilePath): List<FileNode>?

    suspend fun node(path: FilePath): FileNode?

    /** Поток на чтение файла; `null` для директории или отсутствующего пути. */
    suspend fun read(path: FilePath): InputStream?

    /** @return путь созданного файла — имя может отличаться от запрошенного при конфликте. */
    suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath?

    suspend fun write(path: FilePath): OutputStream?
}
