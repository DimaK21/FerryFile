package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import javax.inject.Inject

class ListDirectoryUseCase @Inject constructor(
    private val storage: FileStorageRepository
) {

    suspend operator fun invoke(path: FilePath): List<FileNode>? =
        if (path.isRoot) storage.listRoot() else storage.list(path)
}
