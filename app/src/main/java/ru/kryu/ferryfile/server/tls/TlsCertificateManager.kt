package ru.kryu.ferryfile.server.tls

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.security.auth.x500.X500Principal
import javax.inject.Inject
import javax.inject.Singleton

data class TlsConfiguration(
    val host: String,
    val keyStore: KeyStore,
    val keyAlias: String,
    val password: String,
    val fingerprint: String
)

/** Creates a persistent, per-installation certificate for the local HTTPS server. */
@Singleton
class TlsCertificateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SharedPreferences
) {

    private val lock = Any()
    private var cached: TlsConfiguration? = null

    fun prepare(host: String?): TlsConfiguration = synchronized(lock) {
        val normalizedHost = normalizeHost(host)
        cached?.takeIf { it.host == normalizedHost }?.let { return it }

        loadExisting(normalizedHost)?.let {
            cached = it
            return it
        }

        generate(normalizedHost).also { cached = it }
    }

    private fun loadExisting(host: String): TlsConfiguration? = runCatching {
        val password = preferences.getString(KEY_PASSWORD, null) ?: return null
        if (preferences.getString(KEY_HOST, null) != host) return null

        val file = keyStoreFile()
        if (!file.isFile) return null

        val keyStore = KeyStore.getInstance(KEY_STORE_TYPE)
        file.inputStream().use { keyStore.load(it, password.toCharArray()) }
        if (!keyStore.isKeyEntry(KEY_ALIAS)) return null

        val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate ?: return null
        certificate.checkValidity()
        TlsConfiguration(
            host = host,
            keyStore = keyStore,
            keyAlias = KEY_ALIAS,
            password = password,
            fingerprint = fingerprint(certificate)
        )
    }.getOrNull()

    private fun generate(host: String): TlsConfiguration {
        val password = randomPassword()
        val address = InetAddress.getByName(host)
        val generated = buildKeyStore {
            certificate(KEY_ALIAS) {
                this.password = password
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = 2048
                daysValid = CERTIFICATE_VALIDITY_DAYS
                keyType = KeyType.Server
                subject = X500Principal("CN=FerryFile")
                domains = listOf("localhost")
                ipAddresses = listOf(address)
            }
        }

        val certificate = generated.getCertificate(KEY_ALIAS) as X509Certificate
        val key = generated.getKey(KEY_ALIAS, password.toCharArray())
        val keyStore = KeyStore.getInstance(KEY_STORE_TYPE).apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, key, password.toCharArray(), arrayOf(certificate))
        }
        writeKeyStore(keyStore, password)
        preferences.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PASSWORD, password)
            .apply()

        return TlsConfiguration(
            host = host,
            keyStore = keyStore,
            keyAlias = KEY_ALIAS,
            password = password,
            fingerprint = fingerprint(certificate)
        )
    }

    private fun writeKeyStore(keyStore: KeyStore, password: String) {
        val file = keyStoreFile()
        val temporary = File(file.parentFile, "$KEY_STORE_FILE.tmp")
        temporary.outputStream().use { keyStore.store(it, password.toCharArray()) }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun keyStoreFile(): File = File(context.filesDir, KEY_STORE_FILE)

    private fun normalizeHost(host: String?): String = host?.trim().orEmpty().ifEmpty { LOOPBACK_HOST }

    private fun randomPassword(): String = ByteArray(32)
        .also { SecureRandom().nextBytes(it) }
        .let { Base64.getEncoder().withoutPadding().encodeToString(it) }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

    private companion object {
        const val KEY_ALIAS = "ferryfile-server"
        const val KEY_STORE_TYPE = "PKCS12"
        const val KEY_STORE_FILE = "ferryfile-server.p12"
        const val KEY_HOST = "tls_certificate_host"
        const val KEY_PASSWORD = "tls_keystore_password"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val CERTIFICATE_VALIDITY_DAYS = 3_650L
    }
}
