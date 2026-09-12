package ru.kryu.ferryfile.domain.repository

import ru.kryu.ferryfile.domain.model.AccessPin

/** Одноразовый код доступа, живущий столько же, сколько запущенный сервер. */
interface AccessCodeRepository {

    val current: AccessPin?

    fun issue(): AccessPin

    fun revoke()

    /** Сверка за константное время; `false`, если код не выпущен. */
    fun verify(candidate: String): Boolean
}
