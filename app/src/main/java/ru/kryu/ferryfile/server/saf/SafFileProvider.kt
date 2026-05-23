package ru.kryu.ferryfile.server.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.kryu.ferryfile.data.PreferencesRepository
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafFileProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository
) {
    data class FileInfo(
        val name: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
        val apiPath: String
    )

    private fun grantedRoots(): List<DocumentFile> =
        prefs.safUris.mapNotNull { DocumentFile.fromTreeUri(context, Uri.parse(it)) }

    fun listRoot(): List<FileInfo> = grantedRoots().mapIndexed { i, root ->
        FileInfo(root.name ?: "Folder $i", 0L, root.lastModified(), true, "$i")
    }

    fun listPath(path: String): List<FileInfo>? {
        val dir = resolve(path)?.takeIf { it.isDirectory } ?: return null
        return dir.listFiles().map { child ->
            FileInfo(child.name ?: "", child.length(), child.lastModified(), child.isDirectory, "$path/${child.name}")
        }
    }

    fun resolve(path: String): DocumentFile? {
        val parts = path.trim('/').split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val rootIndex = parts[0].toIntOrNull() ?: return null
        var current: DocumentFile = grantedRoots().getOrNull(rootIndex) ?: return null
        for (segment in parts.drop(1)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    fun createFileInPath(dirPath: String, name: String, mimeType: String): DocumentFile? =
        resolve(dirPath)?.takeIf { it.isDirectory }?.createFile(mimeType, name)

    fun isValidPath(path: String): Boolean {
        if (path == "/") return true
        val rootIndex = path.trim('/').split("/").firstOrNull()?.toIntOrNull() ?: return false
        return rootIndex < grantedRoots().size
    }

    fun openInputStream(path: String): InputStream? {
        val file = resolve(path) ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    fun openOutputStream(docFile: DocumentFile): OutputStream? =
        context.contentResolver.openOutputStream(docFile.uri)
}
