package ru.kryu.ferryfile.data.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Расшаренные папки в том же порядке, в каком они хранятся в настройках: индекс = корень пути. */
@Singleton
class SafRootsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {

    fun roots(): List<DocumentFile?> = settings.sharedFolders.value.map { folder ->
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folder.uri)) }.getOrNull()
    }
}
