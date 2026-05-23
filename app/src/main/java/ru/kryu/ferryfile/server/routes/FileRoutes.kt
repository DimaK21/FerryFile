package ru.kryu.ferryfile.server.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import ru.kryu.ferryfile.server.saf.SafFileProvider
import ru.kryu.ferryfile.server.transfer.DownloadHandler
import ru.kryu.ferryfile.server.transfer.TransferEvent
import ru.kryu.ferryfile.server.transfer.TransferProgress
import ru.kryu.ferryfile.server.transfer.UploadHandler
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

fun Application.configureFileRoutes(
    safFileProvider: SafFileProvider,
    transferProgress: TransferProgress,
    downloadHandler: DownloadHandler,
    uploadHandler: UploadHandler
) {
    routing {
        get("/login") {
            val html = loadAsset("webui/login.html") ?: "<html>Login</html>"
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
            val content = loadAsset("webui/$path") ?: run {
                call.respond(HttpStatusCode.NotFound); return@get
            }
            call.respondText(content, contentType)
        }

        get("/") {
            call.respondRedirect("/login")
        }

        authenticate("session") {
            get("/files") {
                val html = loadAsset("webui/files.html") ?: "<html>Files</html>"
                call.respondText(html, ContentType.Text.Html)
            }

            get("/api/list") {
                val path = call.request.queryParameters["path"] ?: "/"
                val items = if (path == "/") {
                    safFileProvider.listRoot()
                } else {
                    if (!safFileProvider.isValidPath(path)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid path"); return@get
                    }
                    safFileProvider.listPath(path) ?: run {
                        call.respond(HttpStatusCode.NotFound); return@get
                    }
                }
                call.respond(FileListResponse(path, items.map {
                    FileItemDto(it.name, it.size, it.lastModified, it.isDirectory, it.apiPath)
                }))
            }

            get("/api/download") {
                val path = call.request.queryParameters["path"]
                    ?: run { call.respond(HttpStatusCode.BadRequest, "path required"); return@get }
                if (!safFileProvider.isValidPath(path)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid path"); return@get
                }
                if (!transferProgress.tryMarkBusy()) {
                    call.respond(HttpStatusCode.Conflict, "Transfer already in progress, try again shortly")
                    return@get
                }
                try {
                    val file = safFileProvider.resolve(path)?.takeIf { !it.isDirectory }
                        ?: run { call.respond(HttpStatusCode.NotFound); return@get }
                    val totalSize = file.length()
                    val fileName = file.name ?: path.substringAfterLast('/')
                    val entry = DownloadHandler.Entry(fileName, totalSize) {
                        safFileProvider.openInputStream(path)
                            ?: throw IOException("Cannot open $path")
                    }
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, "ferryfile.zip")
                            .toString()
                    )
                    call.respondOutputStream(ContentType.Application.Zip) {
                        downloadHandler.streamZip(listOf(entry), this) { bytes ->
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
                val dirPath = call.request.queryParameters["path"]
                    ?: run { call.respond(HttpStatusCode.BadRequest, "path required"); return@post }
                if (!safFileProvider.isValidPath(dirPath)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid path"); return@post
                }
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
                                val destFile = safFileProvider.createFileInPath(
                                    dirPath, fileName,
                                    part.contentType?.toString() ?: "application/octet-stream"
                                )
                                if (destFile != null) {
                                    var fileBytes = 0L
                                    safFileProvider.openOutputStream(destFile)?.use { out ->
                                        part.streamProvider().use { input ->
                                            uploadHandler.writeEntry(input, out) { cumulative ->
                                                fileBytes = cumulative
                                                transferProgress.tryEmit(
                                                    TransferEvent.Progress(fileName, totalBytes + cumulative, -1L, 0, 0)
                                                )
                                            }
                                        }
                                    }
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

private fun Application.loadAsset(path: String): String? =
    environment.classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
