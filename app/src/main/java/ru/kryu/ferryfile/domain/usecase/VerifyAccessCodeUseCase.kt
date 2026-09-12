package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.repository.AccessCodeRepository
import javax.inject.Inject

class VerifyAccessCodeUseCase @Inject constructor(
    private val accessCodes: AccessCodeRepository
) {

    operator fun invoke(candidate: String): Boolean = accessCodes.verify(candidate)
}
