package ru.kryu.ferryfile.data.files

import androidx.documentfile.provider.DocumentFile
import ru.kryu.ferryfile.domain.model.FilePath
import javax.inject.Inject
import javax.inject.Singleton

/** Превращает [FilePath] в [DocumentFile]. Валидация самого пути уже выполнена в [FilePath.parse]. */
@Singleton
class SafPathResolver @Inject constructor(
    private val roots: SafRootsProvider
) {

    fun resolve(path: FilePath): DocumentFile? {
        if (path.isRoot) return null
        val index = path.rootIndex ?: return null
        var current = roots.roots().getOrNull(index) ?: return null
        for (segment in path.segments.drop(1)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}
