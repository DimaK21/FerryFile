package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.usecase.DownloadSelectionUseCase
import ru.kryu.ferryfile.domain.usecase.ListDirectoryUseCase
import ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase
import ru.kryu.ferryfile.server.transfer.DownloadHandler
import ru.kryu.ferryfile.server.transfer.TransferEvent
import ru.kryu.ferryfile.server.transfer.TransferProgress
import java.io.IOException

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
data class ErrorResponse(val error: String)

fun Application.configureFileRoutes(
    listDirectory: ListDirectoryUseCase,
    downloadSelection: DownloadSelectionUseCase,
    saveUpload: SaveUploadUseCase,
    transferProgress: TransferProgress,
    zipStreamWriter: DownloadHandler,
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
                val path = FilePath.parse(call.request.queryParameters["path"])
                    ?: run { call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path")); return@get }
                val items = listDirectory(path)
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")); return@get }
                call.respond(FileListResponse(path.raw, items.map {
                    FileItemDto(it.name, it.sizeBytes, it.lastModified, it.isDirectory, it.path.raw)
                }))
            }

            get("/api/download") {
                val path = FilePath.parse(call.request.queryParameters["path"])
                    ?: run { call.respond(HttpStatusCode.BadRequest, "Invalid path"); return@get }
                if (!transferProgress.tryMarkBusy()) {
                    call.respond(HttpStatusCode.Conflict, "Transfer already in progress, try again shortly")
                    return@get
                }
                try {
                    val selection = downloadSelection.resolve(listOf(path))
                    val single = selection as? DownloadSelectionUseCase.Selection.SingleFile
                        ?: run { call.respond(HttpStatusCode.NotFound); return@get }
                    val fileName = single.node.name
                    val totalSize = single.node.sizeBytes
                    val stream = downloadSelection.open(path)
                        ?: throw IOException("Cannot open ${path.raw}")
                    val entry = DownloadHandler.Entry(fileName, totalSize) { stream }
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, "ferryfile.zip")
                            .toString()
                    )
                    call.respondOutputStream(ContentType.Application.Zip) {
                        zipStreamWriter.streamZip(listOf(entry), this) { bytes ->
                            val pct = if (totalSize > 0) (bytes * 100 / totalSize).toInt() else 0
                            transferProgress.tryEmit(TransferEvent.Progress(fileName, bytes, totalSize, pct, 0))
                        }
                        transferProgress.tryEmit(TransferEvent.Done(1, totalSize))
                    }
                } finally {
                    transferProgress.markIdle()
                }
            }

            post("/api/upload") {
                val dirPath = FilePath.parse(call.request.queryParameters["path"])
                    ?: run { call.respond(HttpStatusCode.BadRequest, "Invalid path"); return@post }
                if (!transferProgress.tryMarkBusy()) {
                    call.respond(HttpStatusCode.Conflict, "Transfer already in progress, try again shortly")
                    return@post
                }
                var filesWritten = 0
                var totalBytes = 0L
                try {
                    val multipart = call.receiveMultipart()
                    var part = multipart.readPart()
                    while (part != null) {
                        try {
                            if (part is PartData.FileItem) {
                                val fileName = part.originalFileName ?: "upload_$filesWritten"
                                val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                                var fileBytes = 0L
                                val result = part.streamProvider().use { input ->
                                    saveUpload(dirPath, fileName, mimeType, input) { cumulative ->
                                        fileBytes = cumulative
                                        transferProgress.tryEmit(
                                            TransferEvent.Progress(fileName, totalBytes + cumulative, -1L, 0, 0)
                                        )
                                    }
                                }
                                if (result is SaveUploadUseCase.Result.Saved) {
                                    totalBytes += fileBytes
                                    filesWritten++
                                }
                            }
                        } finally {
                            part.dispose()
                        }
                        part = multipart.readPart()
                    }
                    call.respond(HttpStatusCode.OK, mapOf("files" to filesWritten, "bytes" to totalBytes))
                } finally {
                    transferProgress.markIdle()
                }
            }
        }
    }
}

private fun loadAsset(assets: AssetManager, path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
