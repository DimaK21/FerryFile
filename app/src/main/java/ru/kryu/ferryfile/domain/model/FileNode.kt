package ru.kryu.ferryfile.domain.model

/** Файл или директория внутри расшаренной папки. */
data class FileNode(
    val path: FilePath,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String
)
