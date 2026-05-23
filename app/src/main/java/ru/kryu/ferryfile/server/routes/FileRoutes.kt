package ru.kryu.ferryfile.server.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import ru.kryu.ferryfile.server.saf.SafFileProvider
import ru.kryu.ferryfile.server.transfer.DownloadHandler
import ru.kryu.ferryfile.server.transfer.TransferEvent
import ru.kryu.ferryfile.server.transfer.TransferProgress
import ru.kryu.ferryfile.server.transfer.UploadHandler

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

        get("/static/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
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
            val session = call.sessions.get<UserSession>()
            if (session != null && session.token.isNotBlank()) {
                call.respondRedirect("/files")
            } else {
                call.respondRedirect("/login")
            }
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
                val paths = call.request.queryParameters.getAll("paths")
                    ?: run { call.respond(HttpStatusCode.BadRequest, "paths required"); return@get }
                if (!paths.all { safFileProvider.isValidPath(it) }) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid path"); return@get
                }
                if (transferProgress.isBusy) {
                    call.respond(HttpStatusCode.Conflict, "Transfer already in progress, try again shortly")
                    return@get
                }
                transferProgress.markBusy()
                try {
                    val totalSize = paths.sumOf { safFileProvider.resolve(it)?.length() ?: 0L }
                    val entries = paths.mapNotNull { path ->
                        val file = safFileProvider.resolve(path)?.takeIf { !it.isDirectory }
                            ?: return@mapNotNull null
                        DownloadHandler.Entry(file.name ?: path.substringAfterLast('/'), file.length()) {
                            call.application.environment.classLoader.getResourceAsStream("")
                                ?: throw UnsupportedOperationException("ContentResolver required on device")
                        }
                    }
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, "ferryfile.zip")
                            .toString()
                    )
                    call.respondOutputStream(ContentType.Application.Zip) {
                        downloadHandler.streamZip(entries, this) { bytes ->
                            val pct = if (totalSize > 0) (bytes * 100 / totalSize).toInt() else 0
                            transferProgress.tryEmit(TransferEvent.Progress("ferryfile.zip", bytes, totalSize, pct, 0))
                        }
                        transferProgress.tryEmit(TransferEvent.Done(entries.size, totalSize))
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
                if (transferProgress.isBusy) {
                    call.respond(HttpStatusCode.Conflict, "Transfer already in progress, try again shortly")
                    return@post
                }
                transferProgress.markBusy()
                var filesWritten = 0
                var totalBytes = 0L
                try {
                    val multipart = call.receiveMultipart()
                    var part = multipart.readPart()
                    while (part != null) {
                        if (part is PartData.FileItem) {
                            val fileName = part.originalFileName ?: "upload_$filesWritten"
                            val destFile = safFileProvider.createFileInPath(
                                dirPath, fileName,
                                part.contentType?.toString() ?: "application/octet-stream"
                            )
                            if (destFile != null) {
                                filesWritten++
                            }
                        }
                        part.dispose()
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
    environment.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
