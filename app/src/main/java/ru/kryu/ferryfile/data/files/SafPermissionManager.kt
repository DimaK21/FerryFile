package ru.kryu.ferryfile.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Работа с постоянными разрешениями SAF и отображаемыми именами папок. */
@Singleton
class SafPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun takePersistable(uri: String): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uri), flags)
    }.isSuccess

    fun release(uri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags) }
    }

    fun displayName(uri: String): String =
        runCatching { Uri.decode(Uri.parse(uri).lastPathSegment) }.getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: uri
}
