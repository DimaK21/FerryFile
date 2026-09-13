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
        val usedNames = mutableSetOf<String>()
        val skipped = mutableListOf<String>()

        for (entry in entries) {
            val source = entry.openStream()
            if (source == null) {
                skipped += entry.name
                continue
            }
            // Entered before putNextEntry so the source stays guaranteed to close even when
            // putNextEntry itself throws — e.g. a client disconnect mid-archive, since writing
            // the local file header is what actually touches the (now broken) response stream.
            source.use { stream ->
                zip.putNextEntry(ZipEntry(uniqueName(entry.name, usedNames)))
                stream.copyTo(zip)
                zip.closeEntry()
            }
        }

        if (skipped.isNotEmpty()) {
            zip.putNextEntry(ZipEntry(uniqueName(SKIPPED_ENTRY_NAME, usedNames)))
            zip.write(skipped.joinToString("\n").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        zip.finish()
    }

    /** Разруливает совпадающие имена записей: `a.txt`, `a (2).txt`, `a (3).txt`, … */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 2
        var candidate: String
        do {
            candidate = "$base ($counter)$extension"
            counter++
        } while (!used.add(candidate))
        return candidate
    }

    private companion object {
        const val SKIPPED_ENTRY_NAME = "_ferryfile-skipped.txt"
    }
}
