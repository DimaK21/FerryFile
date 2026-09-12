package ru.kryu.ferryfile.domain.model

/** TCP-порт сервера. Единственное место, где живёт правило диапазона. */
@JvmInline
value class Port private constructor(val value: Int) {

    companion object {
        const val MIN = 1024
        const val MAX = 65535

        val DEFAULT = Port(8080)

        fun parse(value: Int): Port? = if (value in MIN..MAX) Port(value) else null

        fun parse(text: String): Port? = text.toIntOrNull()?.let { parse(it) }
    }
}
