package ru.kryu.ferryfile.data.server

import ru.kryu.ferryfile.domain.model.AccessPin
import ru.kryu.ferryfile.domain.repository.AccessCodeRepository
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryAccessCodeRepository @Inject constructor() : AccessCodeRepository {

    private val random = SecureRandom()

    @Volatile
    private var pin: AccessPin? = null

    override val current: AccessPin? get() = pin

    override fun issue(): AccessPin {
        val digits = buildString { repeat(AccessPin.LENGTH) { append(random.nextInt(10)) } }
        return AccessPin.of(digits).also { pin = it }
    }

    override fun revoke() {
        pin = null
    }

    override fun verify(candidate: String): Boolean {
        val expected = pin?.digits?.toByteArray(Charsets.UTF_8) ?: return false
        return MessageDigest.isEqual(expected, candidate.toByteArray(Charsets.UTF_8))
    }
}
