package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

fun Application.configureFileRoutes(
    listDirectory: ListDirectoryUseCase,
    downloadSelection: DownloadSelectionUseCase,
    saveUpload: SaveUploadUseCase,
    transferProgress: TransferProgress,
    zipStreamWriter: ZipStreamWriter,
    assets: AssetManager
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
                    val multipart = call.receiveMultipart()
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

                    transferProgress.emitDone(transferId, files, written)
                    call.respond(UploadResponse(files, written))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    transferProgress.emitError(transferId, "upload_failed", e.message ?: "Upload failed")
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("upload_failed"))
                }
            }
        }
    }
}

private fun loadAsset(assets: AssetManager, path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

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
