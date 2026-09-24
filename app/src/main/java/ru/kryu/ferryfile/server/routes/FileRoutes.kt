package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import android.util.Log
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.discard
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.usecase.DownloadSelectionUseCase
import ru.kryu.ferryfile.domain.usecase.ListDirectoryUseCase
import ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase
import ru.kryu.ferryfile.server.transfer.ZipStreamWriter
import ru.kryu.ferryfile.server.transfer.TransferProgress
import java.io.IOException
import java.net.URLEncoder

@Serializable
data class FileListResponse(val path: String, val items: List<FileItemDto>)

@Serializable
data class FileItemDto(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val path: String
)

@Serializable
data class UploadResponse(val files: Int, val bytes: Long)

@Serializable
data class ErrorResponse(val error: String)

private const val BINARY_MIME = "application/octet-stream"
private const val PROGRESS_INTERVAL_MS = 200L
private const val UPLOAD_LOG_TAG = "FerryFileUpload"

/**
 * Bounds how long the upload failure path waits to drain a still-arriving request body after
 * an upload aborts (see the catch block below) — long enough for a normal LAN client to finish
 * pushing what it already has queued, bounded so a client that stops sending entirely can't
 * pin the connection open forever.
 */
private const val DRAIN_TIMEOUT_MS = 15_000L

/**
 * Ktor's `call.receiveMultipart(formFieldLimit = ...)` caps every multipart *part* body at
 * this many bytes — despite its name, that is not limited to text form fields: the CIO
 * multipart parser (`CIOMultipartDataBase` -> `parsePartBodyImpl` -> `ByteReadChannel.readUntil`,
 * ktor-http-cio 3.1.3) applies the same limit to file parts, throwing `IOException` once it is
 * exceeded. Left at its default of 50 MiB (`DEFAULT_FORM_FIELD_LIMIT` in Ktor's
 * `ApplicationReceiveFunctionsJvm.kt`, 52_428_800 bytes), this is exactly what silently stalled
 * a 75 MiB upload on-device: the parser threw mid-file, and the client was left hanging (see
 * the catch block below for why, and how that failure path is now fixed).
 *
 * The fix is *not* to raise this to `Long.MAX_VALUE`. `CIOMultipartDataBase.partToData` only
 * hands a part to the caller as a stream when it has a filename (a real file); a part
 * *without* one is fully materialised in memory by Ktor itself — via `body.readRemaining()`,
 * then again as a `String` — before this route ever gets a chance to inspect or reject it. An
 * unbounded limit would let any authenticated peer on the LAN OOM-kill the foreground service
 * with a single giant non-file part, which is a strictly worse hole than the 50 MiB default
 * this fix set out to raise.
 *
 * Instead, the upload route bounds this per request by that request's own declared
 * `Content-Length`: a request that says it is 75 MiB is allowed to buffer up to 75 MiB for a
 * part, which is a no-op ceiling for the file part FerryFile actually receives — it streams
 * straight to disk via `SaveUploadUseCase`/`saveUpload` and never buffers it in memory, so its
 * size was never really the risk — while still bounding what an absent or lying
 * `Content-Length` can make Ktor buffer. [FALLBACK_MULTIPART_PART_LIMIT_BYTES] is what applies
 * when the header is missing or unparseable (a non-positive value is never passed to
 * `receiveMultipart` — Ktor's own default would otherwise apply, 50 MiB), since that case
 * can't be trusted to bound anything on its own.
 *
 * The declared length itself is *client-supplied* and not otherwise validated, though: without
 * a ceiling, a peer could send `Content-Length: 9223372036854775807` and get an effectively
 * unbounded limit again — worse than the original 50 MiB default this whole fix exists to
 * raise, on an endpoint any authenticated LAN peer can reach. [HARD_MULTIPART_PART_LIMIT_CEILING_BYTES]
 * clamps the declared value: comfortably above any realistic single file this app is for (a
 * phone photo burst or video clip — the on-device repro was 75 MiB), while small enough that a
 * worst-case filename-less part fully buffered up to it (`readRemaining()`, then duplicated
 * again as a `String`) stays a bounded, finite allocation instead of an attacker-chosen one —
 * this app declares no `android:largeHeap`, so the default per-process heap ceiling is what a
 * part buffered up to this size has to survive within.
 */
private const val FALLBACK_MULTIPART_PART_LIMIT_BYTES = 8L * 1024 * 1024 // 8 MiB
private const val HARD_MULTIPART_PART_LIMIT_CEILING_BYTES = 256L * 1024 * 1024 // 256 MiB

fun Application.configureFileRoutes(
    listDirectory: ListDirectoryUseCase,
    downloadSelection: DownloadSelectionUseCase,
    saveUpload: SaveUploadUseCase,
    transferProgress: TransferProgress,
    zipStreamWriter: ZipStreamWriter,
    assets: AssetManager,
    // Seam so local JVM unit tests don't have to go through android.util.Log (a stub that
    // throws unless mocked): production wiring leaves this at its default, which is the
    // exact same Log.e call this route always made. Tests can pass their own to assert a
    // failure was logged, or a no-op to keep quiet, without any test-only framework flag.
    logError: (String, Throwable) -> Unit = { message, cause -> Log.e(UPLOAD_LOG_TAG, message, cause) }
) {
    routing {
        get("/login") {
            val html = loadAsset(assets, "webui/login.html") ?: run {
                call.respond(HttpStatusCode.InternalServerError); return@get
            }
            call.respondText(html, ContentType.Text.Html)
        }

        get("/webui/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (path.contains("..") || path.startsWith("/")) {
                call.respond(HttpStatusCode.BadRequest); return@get
            }
            val contentType = when {
                path.endsWith(".css") -> ContentType.Text.CSS
                path.endsWith(".js") -> ContentType.Application.JavaScript
                else -> ContentType.Text.Plain
            }
            val content = loadAsset(assets, "webui/$path") ?: run {
                call.respond(HttpStatusCode.NotFound); return@get
            }
            call.respondText(content, contentType)
        }

        get("/") {
            call.respondRedirect("/login")
        }

        authenticate("session") {
            get("/files") {
                val html = loadAsset(assets, "webui/files.html") ?: run {
                    call.respond(HttpStatusCode.InternalServerError); return@get
                }
                call.respondText(html, ContentType.Text.Html)
            }

            get("/api/list") {
                val path = FilePath.parse(call.request.queryParameters["path"] ?: "/")
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path"))
                        return@get
                    }
                val items = listDirectory(path)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                        return@get
                    }
                call.respond(
                    FileListResponse(
                        path = path.raw,
                        items = items.map {
                            FileItemDto(it.name, it.sizeBytes, it.lastModified, it.isDirectory, it.path.raw)
                        }
                    )
                )
            }

            get("/api/download") {
                val rawPaths = call.request.queryParameters.getAll("path").orEmpty()
                val paths = rawPaths.mapNotNull { FilePath.parse(it) }
                if (rawPaths.isEmpty() || paths.size != rawPaths.size) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path"))
                    return@get
                }

                when (val selection = downloadSelection.resolve(paths)) {
                    DownloadSelectionUseCase.Selection.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))

                    is DownloadSelectionUseCase.Selection.SingleFile -> {
                        val node = selection.node
                        val stream = downloadSelection.open(node.path)
                        if (stream == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                            return@get
                        }
                        // Opened eagerly, before headers are written, so it must stay guaranteed
                        // to close even if the header write, respondOutputStream itself, or the
                        // body throws before ever invoking its lambda (e.g. the client already
                        // disconnected) — spanning the whole path in `stream.use` covers all of
                        // that, unlike closing only inside the body lambda.
                        stream.use { source ->
                            call.response.header(HttpHeaders.ContentDisposition, attachmentHeader(node.name))
                            val contentType = runCatching { ContentType.parse(node.mimeType) }
                                .getOrDefault(ContentType.Application.OctetStream)
                            // Length goes to the content, not a manually set header: Ktor derives
                            // Transfer-Encoding: chunked from content.headers alone, so a header
                            // set by hand on call.response has no effect on that decision and the
                            // response ends up with both a Content-Length and a contradictory
                            // chunked encoding.
                            call.respondOutputStream(contentType, contentLength = node.sizeBytes.takeIf { it > 0 }) {
                                withContext(Dispatchers.IO) { source.copyTo(this@respondOutputStream) }
                            }
                        }
                    }

                    is DownloadSelectionUseCase.Selection.Archive -> {
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            attachmentHeader(selection.fileName)
                        )
                        call.respondOutputStream(ContentType.Application.Zip) {
                            val entries = selection.entries.map { source ->
                                ZipStreamWriter.Entry(source.entryName) { downloadSelection.open(source.path) }
                            }
                            withContext(Dispatchers.IO) {
                                zipStreamWriter.write(entries, this@respondOutputStream)
                            }
                        }
                    }
                }
            }

            post("/api/upload") {
                val dir = FilePath.parse(call.request.queryParameters["path"])
                if (dir == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path"))
                    return@post
                }
                if (dir.isRoot) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("root_not_writable"))
                    return@post
                }

                val transferId = call.request.queryParameters["transferId"].orEmpty()
                val totalBytes = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: -1L
                val startedAt = System.currentTimeMillis()
                var written = 0L
                var files = 0
                var lastEmitAt = 0L

                try {
                    // See FALLBACK_MULTIPART_PART_LIMIT_BYTES's doc comment: bound by this
                    // request's own declared size so a declared-75-MiB upload is allowed its
                    // 75 MiB, while an absent/unparseable Content-Length can't buffer unbounded
                    // — and clamped by HARD_MULTIPART_PART_LIMIT_CEILING_BYTES so a *lying*
                    // declared size (client-supplied, unverified) can't buffer unbounded either.
                    val multipartPartLimit = totalBytes.takeIf { it > 0 }
                        ?.let { minOf(it, HARD_MULTIPART_PART_LIMIT_CEILING_BYTES) }
                        ?: FALLBACK_MULTIPART_PART_LIMIT_BYTES

                    // receiveMultipart()'s parser runs as a child coroutine of whatever job is
                    // active when it's called (Ktor parents it on the ambient
                    // PipelineContext.coroutineContext — see DefaultTransformJvm.multiPartData).
                    // Left unwrapped, a parser-level failure (e.g. the limit above, or a bogus
                    // per-part Content-Length) cancels *this handler's own job* via ordinary
                    // structured-concurrency child-failure propagation, which then re-fires at
                    // this handler's very next suspension point regardless of whether the catch
                    // block below already handled that same exception — so the request would
                    // still fail without ever delivering our response. supervisorScope isolates
                    // that: the parser's failure still reaches us exactly the same way (its
                    // channel is closed with that cause, and readPart() throws it normally), it
                    // just no longer cancels this handler's own job as a side effect.
                    supervisorScope {
                        val multipart = call.receiveMultipart(formFieldLimit = multipartPartLimit)
                        var part = multipart.readPart()
                        while (part != null) {
                            try {
                                if (part is PartData.FileItem) {
                                    val fileName = part.originalFileName.orEmpty()
                                    val mimeType = part.contentType?.toString() ?: BINARY_MIME
                                    val alreadyWritten = written
                                    val source = part.provider().toInputStream()

                                    val result = saveUpload(dir, fileName, mimeType, source) { chunkBytes ->
                                        val now = System.currentTimeMillis()
                                        if (now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                                            lastEmitAt = now
                                            transferProgress.emitProgress(
                                                transferId, fileName,
                                                alreadyWritten + chunkBytes, totalBytes, startedAt
                                            )
                                        }
                                    }

                                    when (result) {
                                        is SaveUploadUseCase.Result.Saved -> {
                                            written = alreadyWritten + result.bytesWritten
                                            files++
                                        }

                                        SaveUploadUseCase.Result.RootNotWritable,
                                        SaveUploadUseCase.Result.Failed ->
                                            throw IOException("Cannot store $fileName")
                                    }
                                }
                            } finally {
                                part.dispose()
                            }
                            part = multipart.readPart()
                        }
                    }

                    transferProgress.emitDone(transferId, files, written)
                    call.respond(UploadResponse(files, written))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Logged via the logError seam (android.util.Log by default), not Ktor's
                    // call.application.log: this app ships no SLF4J binding, so Ktor's own
                    // logger is a silent no-op here — the original silent-failure symptom
                    // included the fact that nothing reached logcat even though this catch
                    // block did run.
                    logError(
                        "Upload failed transferId=$transferId dir=${dir.raw} " +
                            "filesWritten=$files bytesWritten=$written",
                        e
                    )
                    transferProgress.emitError(transferId, "upload_failed", e.message ?: "Upload failed")

                    // Draining the *multipart* reader would not help here: when the parser
                    // itself is what failed (e.g. a part past the limit above), Ktor's
                    // CIOMultipartDataBase.readPart() rethrows that very same cause on the next
                    // call — its `events` channel was closed with it, and readPart() only treats
                    // a plain ClosedReceiveChannelException as "no more parts" — so it can never
                    // actually drain anything in exactly the case that matters. The bytes that
                    // still need draining live in the *raw* request channel instead, which the
                    // engine keeps feeding independently of whether the multipart parser above
                    // is alive, and which has no "already consumed" guard — so it's still there
                    // to read no matter what receiveMultipart() did. Left undrained, a client
                    // still mid-stream backs up against a socket the server stopped reading,
                    // and call.respond() below can hang indefinitely instead of ever reaching
                    // it. Bounded by a timeout so a client that stops sending entirely can't
                    // pin the connection open forever.
                    try {
                        withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) { call.request.receiveChannel().discard() }
                        }
                    } catch (drainFailure: CancellationException) {
                        throw drainFailure
                    } catch (drainFailure: Exception) {
                        logError(
                            "Failed to drain the aborted upload's request body transferId=$transferId",
                            drainFailure
                        )
                    }

                    try {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("upload_failed"))
                    } catch (respondFailure: CancellationException) {
                        throw respondFailure
                    } catch (respondFailure: Exception) {
                        logError(
                            "Could not deliver upload_failed response transferId=$transferId",
                            respondFailure
                        )
                    }
                }
            }
        }
    }
}

/**
 * `Content-Disposition` с ASCII-запасным именем и RFC 5987-формой для кириллицы и прочего
 * не-ASCII. Без `filename*` браузер сохранит файл под искажённым именем.
 */
internal fun attachmentHeader(fileName: String): String {
    val asciiFallback = fileName.map { char ->
        if (char.code in 32..126 && char != '"' && char != '\\') char else '_'
    }.joinToString("")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
    return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}

private fun loadAsset(assets: AssetManager, path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
