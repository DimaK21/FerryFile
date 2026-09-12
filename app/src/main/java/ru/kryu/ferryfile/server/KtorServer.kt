package ru.kryu.ferryfile.server

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
import ru.kryu.ferryfile.domain.usecase.DownloadSelectionUseCase
import ru.kryu.ferryfile.domain.usecase.ListDirectoryUseCase
import ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase
import ru.kryu.ferryfile.server.auth.PasswordHasher
import ru.kryu.ferryfile.server.auth.SessionManager
import ru.kryu.ferryfile.server.routes.configureAuthRoutes
import ru.kryu.ferryfile.server.routes.configureFileRoutes
import ru.kryu.ferryfile.server.routes.configureSseRoutes
import ru.kryu.ferryfile.server.transfer.TransferProgress
import ru.kryu.ferryfile.server.transfer.ZipStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val passwordHasher: PasswordHasher,
    private val listDirectory: ListDirectoryUseCase,
    private val downloadSelection: DownloadSelectionUseCase,
    private val saveUpload: SaveUploadUseCase,
    private val transferProgress: TransferProgress,
    private val zipStreamWriter: ZipStreamWriter,
    private val prefs: SharedPreferences
) {
    @Volatile private var engine: EmbeddedServer<*, *>? = null

    // до Задачи 8 пароль читается напрямую из SharedPreferences
    private val passwordHash: String get() = prefs.getString("password_hash", "") ?: ""

    fun start(port: Int) {
        sessionManager.reset()
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0", watchPaths = emptyList()) {
            install(ContentNegotiation) { json() }
            install(SSE)
            configureAuthRoutes(sessionManager, { passwordHash }, passwordHasher)
            configureFileRoutes(
                listDirectory,
                downloadSelection,
                saveUpload,
                transferProgress,
                zipStreamWriter,
                context.assets
            )
            configureSseRoutes(transferProgress)
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        engine = null
        sessionManager.reset()
    }

    val isRunning: Boolean get() = engine != null
}
