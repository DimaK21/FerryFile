package ru.kryu.ferryfile.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasswordHasher @Inject constructor() {

    companion object {
        private const val ITERATIONS = 310_000
        private const val KEY_LENGTH = 256
        private const val SALT_BYTES = 16
    }

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return "${salt.toHex()}:${pbkdf2(password, salt).toHex()}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val saltHex = parts[0]
        val hashHex = parts[1]
        if (saltHex.length % 2 != 0 || hashHex.length % 2 != 0) return false
        val salt = saltHex.fromHex()
        return MessageDigest.isEqual(pbkdf2(password, salt), hashHex.fromHex())
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        check(length % 2 == 0)
        return ByteArray(length / 2) { i ->
            ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
