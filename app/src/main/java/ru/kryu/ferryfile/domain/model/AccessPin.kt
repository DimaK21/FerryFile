package ru.kryu.ferryfile.domain.model

/** Одноразовый код доступа, действующий пока запущен сервер. */
@JvmInline
value class AccessPin private constructor(val digits: String) {

    companion object {
        const val LENGTH = 6

        fun parse(raw: String): AccessPin? =
            if (raw.length == LENGTH && raw.all { it in '0'..'9' }) AccessPin(raw) else null

        fun of(digits: String): AccessPin =
            requireNotNull(parse(digits)) { "PIN must be exactly $LENGTH digits" }
    }
}
