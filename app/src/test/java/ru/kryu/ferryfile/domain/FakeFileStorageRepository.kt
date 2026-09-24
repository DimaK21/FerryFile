package ru.kryu.ferryfile.domain

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Хранилище в памяти: дерево задаётся через [addDirectory] и [addFile]. */
class FakeFileStorageRepository : FileStorageRepository {

    /** Содержимое, записанное через [write], по сырому пути. */
    val writtenFiles = LinkedHashMap<String, ByteArrayOutputStream>()

    var createFileFails = false

    private val nodes = LinkedHashMap<String, FileNode>()
    private val fileContents = LinkedHashMap<String, ByteArray>()

    fun addDirectory(raw: String): FileNode = put(raw, isDirectory = true, content = null)

    fun addFile(raw: String, content: String = "", mimeType: String = "text/plain"): FileNode {
        val node = put(raw, isDirectory = false, content = content.toByteArray(), mimeType = mimeType)
        return node
    }

    override suspend fun listRoot(): List<FileNode> =
        nodes.values.filter { it.path.segments.size == 1 }

    override suspend fun list(path: FilePath): List<FileNode>? {
        val dir = nodes[path.raw] ?: return null
        if (!dir.isDirectory) return null
        val depth = path.segments.size
        return nodes.values.filter {
            it.path.segments.size == depth + 1 && it.path.raw.startsWith("${path.raw}/")
        }
    }

    override suspend fun node(path: FilePath): FileNode? = nodes[path.raw]

    override suspend fun read(path: FilePath): InputStream? =
        fileContents[path.raw]?.let { ByteArrayInputStream(it) }

    override suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath? {
        if (createFileFails) return null
        val parent = nodes[dir.raw] ?: return null
        if (!parent.isDirectory) return null
        val created = dir.child(name) ?: return null
        put(created.raw, isDirectory = false, content = ByteArray(0), mimeType = mimeType)
        return created
    }

    override suspend fun write(path: FilePath): OutputStream? {
        if (nodes[path.raw] == null) return null
        return ByteArrayOutputStream().also { writtenFiles[path.raw] = it }
    }

    private fun put(
        raw: String,
        isDirectory: Boolean,
        content: ByteArray?,
        mimeType: String = "application/octet-stream"
    ): FileNode {
        val path = requireNotNull(FilePath.parse(raw)) { "Invalid test path: $raw" }
        val node = FileNode(
            path = path,
            name = path.name,
            sizeBytes = content?.size?.toLong() ?: 0L,
            lastModified = 0L,
            isDirectory = isDirectory,
            mimeType = if (isDirectory) "vnd.android.document/directory" else mimeType
        )
        nodes[path.raw] = node
        if (content != null) fileContents[path.raw] = content
        return node
    }
}
