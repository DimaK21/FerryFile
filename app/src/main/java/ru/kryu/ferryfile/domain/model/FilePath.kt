package ru.kryu.ferryfile.domain.model

/**
 * Путь API-уровня: `<индекс расшаренной папки>[/<сегмент>]*`, например `0/docs/report.pdf`.
 * Отдельно существует виртуальный корень [ROOT] — список расшаренных папок, а не директория.
 * Единственное место, где разбирается и валидируется путь, пришедший от клиента.
 */
@JvmInline
value class FilePath private constructor(val raw: String) {

    val isRoot: Boolean get() = raw == ROOT_RAW

    val segments: List<String> get() = if (isRoot) emptyList() else raw.split(SEPARATOR)

    val rootIndex: Int? get() = segments.firstOrNull()?.toIntOrNull()

    val name: String get() = segments.lastOrNull() ?: ""

    /** Путь к элементу внутри этой директории; `null`, если имя недопустимо или это корень. */
    fun child(name: String): FilePath? {
        if (isRoot || !isValidSegment(name)) return null
        return FilePath(raw + SEPARATOR + name)
    }

    companion object {
        val ROOT = FilePath(ROOT_RAW)

        private const val ROOT_RAW = "/"
        private const val SEPARATOR = '/'

        fun root(index: Int): FilePath = FilePath(index.toString())

        fun parse(raw: String?): FilePath? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed == ROOT_RAW) return ROOT
            val normalized = trimmed.trim(SEPARATOR)
            if (normalized.isEmpty()) return null
            val segments = normalized.split(SEPARATOR)
            if (segments.any { !isValidSegment(it) }) return null
            val index = segments.first().toIntOrNull() ?: return null
            if (index < 0) return null
            return FilePath(segments.joinToString(SEPARATOR.toString()))
        }

        private fun isValidSegment(segment: String): Boolean =
            segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains(SEPARATOR)
    }
}
