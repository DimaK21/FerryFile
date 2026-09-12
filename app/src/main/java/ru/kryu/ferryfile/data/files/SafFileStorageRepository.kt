package ru.kryu.ferryfile.data.files

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafFileStorageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roots: SafRootsProvider,
    private val resolver: SafPathResolver
) : FileStorageRepository {

    override suspend fun listRoot(): List<FileNode> = withContext(Dispatchers.IO) {
        roots.roots().mapIndexed { index, root ->
            FileNode(
                path = FilePath.root(index),
                name = root.name ?: "Folder ${index + 1}",
                sizeBytes = 0L,
                lastModified = root.lastModified(),
                isDirectory = true,
                mimeType = MIME_DIRECTORY
            )
        }
    }

    override suspend fun list(path: FilePath): List<FileNode>? = withContext(Dispatchers.IO) {
        val dir = resolver.resolve(path)?.takeIf { it.isDirectory } ?: return@withContext null
        dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val childPath = path.child(name) ?: return@mapNotNull null
            child.toNode(childPath, name)
        }
    }

    override suspend fun node(path: FilePath): FileNode? = withContext(Dispatchers.IO) {
        val file = resolver.resolve(path) ?: return@withContext null
        file.toNode(path, file.name ?: path.name)
    }

    override suspend fun read(path: FilePath): InputStream? = withContext(Dispatchers.IO) {
        val file = resolver.resolve(path)?.takeIf { !it.isDirectory } ?: return@withContext null
        runCatching { context.contentResolver.openInputStream(file.uri) }.getOrNull()
    }

    override suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath? =
        withContext(Dispatchers.IO) {
            val parent = resolver.resolve(dir)?.takeIf { it.isDirectory } ?: return@withContext null
            val created = parent.createFile(mimeType, name) ?: return@withContext null
            dir.child(created.name ?: name)
        }

    override suspend fun write(path: FilePath): OutputStream? = withContext(Dispatchers.IO) {
        val file = resolver.resolve(path)?.takeIf { !it.isDirectory } ?: return@withContext null
        runCatching { context.contentResolver.openOutputStream(file.uri) }.getOrNull()
    }

    private fun DocumentFile.toNode(path: FilePath, name: String) = FileNode(
        path = path,
        name = name,
        sizeBytes = if (isDirectory) 0L else length(),
        lastModified = lastModified(),
        isDirectory = isDirectory,
        mimeType = type ?: if (isDirectory) MIME_DIRECTORY else MIME_BINARY
    )

    private companion object {
        const val MIME_DIRECTORY = "vnd.android.document/directory"
        const val MIME_BINARY = "application/octet-stream"
    }
}
