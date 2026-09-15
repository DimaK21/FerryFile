package ru.kryu.ferryfile.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
import ru.kryu.ferryfile.domain.usecase.DownloadSelectionUseCase
import ru.kryu.ferryfile.domain.usecase.ListDirectoryUseCase
import ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase
import ru.kryu.ferryfile.domain.usecase.VerifyAccessCodeUseCase
import ru.kryu.ferryfile.server.auth.SessionManager
import ru.kryu.ferryfile.server.routes.configureAuthRoutes
import ru.kryu.ferryfile.server.routes.configureFileRoutes
import ru.kryu.ferryfile.server.routes.configureSseRoutes
import ru.kryu.ferryfile.server.tls.TlsCertificateManager
import ru.kryu.ferryfile.server.tls.TlsConfiguration
import ru.kryu.ferryfile.server.transfer.TransferProgress
import ru.kryu.ferryfile.server.transfer.ZipStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val verifyAccessCode: VerifyAccessCodeUseCase,
    private val listDirectory: ListDirectoryUseCase,
    private val downloadSelection: DownloadSelectionUseCase,
    private val saveUpload: SaveUploadUseCase,
    private val transferProgress: TransferProgress,
    private val zipStreamWriter: ZipStreamWriter,
    private val tlsCertificateManager: TlsCertificateManager
) {
    @Volatile private var engine: EmbeddedServer<*, *>? = null
    private var preparedTls: TlsConfiguration? = null

    @Synchronized
    fun prepareTls(host: String?): String? {
        val configuration = tlsCertificateManager.prepare(host)
        preparedTls = configuration
        return configuration.fingerprint
    }

    @Synchronized
    fun currentTlsFingerprint(): String? = preparedTls?.fingerprint

    fun start(port: Int, host: String? = null, useHttps: Boolean = false) {
        sessionManager.reset()
        val module: Application.() -> Unit = {
            install(ContentNegotiation) { json() }
            install(SSE)
            configureAuthRoutes(sessionManager, verifyAccessCode, secureCookies = useHttps)
            configureFileRoutes(
                listDirectory,
                downloadSelection,
                saveUpload,
                transferProgress,
                zipStreamWriter,
                context.assets
            )
            configureSseRoutes(transferProgress)
        }

        engine = if (useHttps) {
            val tls = synchronized(this) {
                preparedTls ?: tlsCertificateManager.prepare(host).also { preparedTls = it }
            }
            embeddedServer(
                Netty,
                configure = {
                    enableHttp2 = false
                    sslConnector(
                        keyStore = tls.keyStore,
                        keyAlias = tls.keyAlias,
                        keyStorePassword = { tls.password.toCharArray() },
                        privateKeyPassword = { tls.password.toCharArray() }
                    ) {
                        this.host = "0.0.0.0"
                        this.port = port
                        enabledProtocols = listOf("TLSv1.2", "TLSv1.3")
                    }
                },
                module = module
            )
        } else {
            synchronized(this) { preparedTls = null }
            embeddedServer(
                Netty,
                port = port,
                host = "0.0.0.0",
                module = module
            )
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        engine = null
        synchronized(this) { preparedTls = null }
        sessionManager.reset()
    }

    val isRunning: Boolean get() = engine != null
}
