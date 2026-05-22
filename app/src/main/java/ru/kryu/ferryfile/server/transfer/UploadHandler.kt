package ru.kryu.ferryfile.server.transfer

import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadHandler @Inject constructor() {

    /** Copies [input] into [output] in chunks. Caller is responsible for closing both streams. */
    fun writeEntry(input: InputStream, output: OutputStream, onBytesWritten: (Long) -> Unit) {
        var total = 0L
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        while (input.read(buf).also { read = it } != -1) {
            output.write(buf, 0, read)
            total += read
            onBytesWritten(total)
        }
        output.flush()
    }
}
