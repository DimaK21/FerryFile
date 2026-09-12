package ru.kryu.ferryfile.server.transfer

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Пишет выбранные файлы в ZIP на лету, не собирая архив в памяти. */
@Singleton
class ZipStreamWriter @Inject constructor() {

    data class Entry(val name: String, val openStream: suspend () -> InputStream?)

    suspend fun write(entries: List<Entry>, output: OutputStream) {
        val zip = ZipOutputStream(output)
        for (entry in entries) {
            val source = entry.openStream() ?: continue
            zip.putNextEntry(ZipEntry(entry.name))
            source.use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }
}
