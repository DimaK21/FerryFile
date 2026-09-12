package ru.kryu.ferryfile.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Слой domain не должен знать про Android, Ktor и Hilt-Android.
 * Рабочая директория юнит-тестов Gradle — каталог модуля (`app/`).
 */
class DomainLayerPurityTest {

    private val forbiddenPrefixes = listOf(
        "android.",
        "androidx.",
        "io.ktor.",
        "dagger.hilt.android.",
        "com.google.zxing."
    )

    @Test fun `domain sources have no framework imports`() {
        val domainDir = File("src/main/java/ru/kryu/ferryfile/domain")
        assertTrue(
            "Каталог domain не найден: ${domainDir.absolutePath}",
            domainDir.isDirectory
        )

        val violations = domainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence()
                    .withIndex()
                    .filter { (_, line) ->
                        val imported = line.trim().removePrefix("import ").removePrefix("kotlin.")
                        line.trim().startsWith("import ") &&
                            forbiddenPrefixes.any { imported.startsWith(it) }
                    }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertEquals("Запрещённые импорты в domain", emptyList<String>(), violations)
    }
}
