package ru.kryu.ferryfile.server.transfer

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadHandler @Inject constructor() {

    data class Entry(val name: String, val size: Long, val openStream: () -> InputStream)

    fun streamZip(entries: List<Entry>, output: OutputStream, onBytesWritten: (Long) -> Unit) {
        var total = 0L
        ZipOutputStream(output).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry.name))
                entry.openStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        zip.write(buf, 0, read)
                        total += read
                        onBytesWritten(total)
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
