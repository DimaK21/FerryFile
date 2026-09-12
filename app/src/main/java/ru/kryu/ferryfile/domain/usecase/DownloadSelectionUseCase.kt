package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.InputStream
import javax.inject.Inject

/**
 * Решает, чем именно является скачивание: одиночным файлом или архивом.
 * Одна папка — архив с её именем, несколько путей — общий архив выборки.
 */
class DownloadSelectionUseCase @Inject constructor(
    private val storage: FileStorageRepository
) {

    data class ZipEntrySource(val entryName: String, val path: FilePath)

    sealed interface Selection {
        data class SingleFile(val node: FileNode) : Selection
        data class Archive(val fileName: String, val entries: List<ZipEntrySource>) : Selection
        data object NotFound : Selection
    }

    suspend fun resolve(paths: List<FilePath>): Selection {
        if (paths.isEmpty() || paths.any { it.isRoot }) return Selection.NotFound

        val nodes = paths.map { storage.node(it) ?: return Selection.NotFound }

        if (nodes.size == 1) {
            val only = nodes.single()
            return if (only.isDirectory) {
                Selection.Archive("${only.name}.zip", collect(only, only.name))
            } else {
                Selection.SingleFile(only)
            }
        }

        return Selection.Archive(SELECTION_ARCHIVE_NAME, nodes.flatMap { collect(it, it.name) })
    }

    suspend fun open(path: FilePath): InputStream? = storage.read(path)

    private suspend fun collect(node: FileNode, entryName: String): List<ZipEntrySource> {
        if (!node.isDirectory) return listOf(ZipEntrySource(entryName, node.path))
        val children = storage.list(node.path) ?: return emptyList()
        return children.flatMap { collect(it, "$entryName/${it.name}") }
    }

    private companion object {
        const val SELECTION_ARCHIVE_NAME = "ferryfile-selection.zip"
    }
}
