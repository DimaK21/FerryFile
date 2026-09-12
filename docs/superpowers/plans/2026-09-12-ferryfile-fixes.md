# FerryFile: исправления и переход на чистую архитектуру — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Починить загрузку файлов с ПК на телефон, сделать скачивание одиночного файла без архива, заменить пароль на одноразовый PIN, привести проект к чистой архитектуре со слоем domain и интерфейсами репозиториев.

**Architecture:** Слои `domain` / `data` / `server` / `ui` внутри единственного Gradle-модуля `:app`. `domain` содержит модели, интерфейсы репозиториев и use cases, не импортирует ничего из `android.*`, `androidx.*`, `io.ktor.*`; `data` реализует репозитории поверх SAF, EncryptedSharedPreferences и системных сервисов; Ktor-роуты и ViewModel'и зависят только от use cases.

**Tech Stack:** Kotlin 2.2.10, AGP 9.1.1, minSdk 30, Jetpack Compose (BOM 2026.02.01, Material3 1.4.0), Ktor 3.1.3 (CIO, SSE, sessions, auth), Hilt 2.59.2 + KSP, kotlinx.serialization 1.7.3, androidx.documentfile, EncryptedSharedPreferences. Тесты: JUnit 4, mockito-kotlin 5.4.0, kotlinx-coroutines-test 1.9.0, ktor-server-test-host.

**Spec:** `docs/superpowers/specs/2026-09-12-ferryfile-fixes-design.md`

## Global Constraints

- Ветка `develop`. Каждая задача завершается отдельным коммитом с зелёными тестами.
- Команда прогона тестов: `./gradlew :app:testDebugUnitTest --console=plain`. Одиночный тест: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.<путь>.<Класс>" --console=plain`.
- Пакет `domain` не должен содержать импортов `android.`, `androidx.`, `io.ktor.`, `dagger.hilt.android.`, `com.google.zxing.`. Это проверяет `DomainLayerPurityTest` (Задача 2).
- Порт валиден в диапазоне 1024..65535 — единственное место этого правила — `Port.parse` (Задача 2).
- PIN — ровно 6 десятичных цифр, `AccessPin.LENGTH = 6`.
- Новые Gradle-зависимости добавлять запрещено. В частности, `material-icons-core` **отсутствует** на classpath (Material3 1.4.0 его не тянет), поэтому `Icons.AutoMirrored.Filled.ArrowBack` использовать нельзя — иконки задаются векторными drawable в `app/src/main/res/drawable/`.
- Тексты интерфейса остаются английскими — приложение сейчас англоязычное, менять язык не в объёме задачи.
- В `app.js` данные с сервера выводятся только через `textContent` / `setAttribute`; `innerHTML` допустим исключительно для статических SVG-литералов без интерполяции.
- Ktor 3 API: у `PartData.FileItem` использовать `provider()` (возвращает `ByteReadChannel`), а не deprecated `streamProvider()`. Конвертация в `InputStream` — `io.ktor.utils.io.jvm.javaio.toInputStream()`.
- Любая блокирующая работа с файлами выполняется в `Dispatchers.IO`.

## Структура файлов

**Создаются (domain):**
- `domain/model/FilePath.kt` — путь API-уровня и его разбор, единственное место валидации путей
- `domain/model/Port.kt`, `domain/model/AccessPin.kt` — value-класcы с валидацией
- `domain/model/FileNode.kt`, `domain/model/SharedFolder.kt` — модели данных
- `domain/model/ServerAddress.kt`, `domain/model/ServerState.kt` — состояние сервера
- `domain/model/TransferEvent.kt` — события передачи (без аннотаций сериализации)
- `domain/repository/SettingsRepository.kt`, `FileStorageRepository.kt`, `NetworkRepository.kt`, `AccessCodeRepository.kt`, `ServerRepository.kt` — интерфейсы
- `domain/usecase/*.kt` — по одному файлу на use case

**Создаются (data):**
- `data/settings/SettingsRepositoryImpl.kt`
- `data/files/SafRootsProvider.kt`, `SafPathResolver.kt`, `SafPermissionManager.kt`, `SafFileStorageRepository.kt`
- `data/network/WifiNetworkRepository.kt`
- `data/server/InMemoryAccessCodeRepository.kt`, `ServerRepositoryImpl.kt`
- `data/di/RepositoryModule.kt` — `@Binds` интерфейс → реализация

**Создаются (ресурсы и web):**
- `res/drawable/ic_arrow_back.xml`, `ic_settings.xml`, `ic_stop.xml`

**Изменяются:** `server/routes/FileRoutes.kt`, `AuthRoutes.kt`, `SseRoutes.kt`, `server/KtorServer.kt`, `server/transfer/*`, `service/FileServerService.kt`, `ui/home/*`, `ui/settings/*`, `MainActivity.kt`, `di/AppModule.kt`, `assets/webui/app.js`, `files.html`, `login.html`, `style.css`, `app/build.gradle.kts`, `gradle/libs.versions.toml`

**Удаляются:** `data/PreferencesRepository.kt`, `server/saf/SafFileProvider.kt`, `server/auth/PasswordHasher.kt` и их тесты

---

### Task 1: Починить компиляцию тестов (базовая линия)

Сейчас тестовый sourceSet не компилируется: `configureFileRoutes` получил пятый параметр `assets`, а тест его не передаёт. До начала рефакторинга ветка должна быть зелёной.

**Files:**
- Modify: `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt:20-36`

**Interfaces:**
- Consumes: ничего
- Produces: зелёный `./gradlew :app:testDebugUnitTest` как база для всех последующих задач

- [ ] **Step 1: Убедиться, что сборка тестов падает**

Run: `./gradlew :app:compileDebugUnitTestKotlin --console=plain`
Expected: FAIL, `FileRoutesTest.kt:34:85 No value passed for parameter 'assets'`

- [ ] **Step 2: Передать мок AssetManager в тесте**

В `FileRoutesTest.kt` добавить импорт `android.content.res.AssetManager`, поле и аргумент:

```kotlin
    private val assets: AssetManager = mock()
```

и в `withApp`:

```kotlin
            configureFileRoutes(safFileProvider, transferProgress, downloadHandler, uploadHandler, assets)
```

- [ ] **Step 3: Прогнать все юнит-тесты**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL. Если красным светится что-то ещё — чинить здесь же, дальше двигаться можно только с зелёной базой.

- [ ] **Step 4: Коммит**

```bash
git add app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt
git commit -m "test: fix FileRoutesTest compilation after AssetManager parameter"
```

---

### Task 2: Модели domain и тест чистоты слоя

**Files:**
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/FilePath.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/Port.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/AccessPin.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/FileNode.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/SharedFolder.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/ServerAddress.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/ServerState.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/model/TransferEvent.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/model/FilePathTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/model/PortTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/model/AccessPinTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/DomainLayerPurityTest.kt`

**Interfaces:**
- Consumes: ничего
- Produces:
  - `FilePath.parse(raw: String?): FilePath?`, `FilePath.ROOT`, `FilePath.root(index: Int): FilePath`, свойства `raw: String`, `isRoot: Boolean`, `segments: List<String>`, `rootIndex: Int?`, `name: String`, метод `child(name: String): FilePath?`
  - `Port.parse(value: Int): Port?`, `Port.parse(text: String): Port?`, `Port.DEFAULT`, `Port.MIN`, `Port.MAX`, свойство `value: Int`
  - `AccessPin.parse(raw: String): AccessPin?`, `AccessPin.of(digits: String): AccessPin`, `AccessPin.LENGTH`, свойство `digits: String`
  - `FileNode(path, name, sizeBytes, lastModified, isDirectory, mimeType)`
  - `SharedFolder(uri: String, displayName: String)`
  - `ServerAddress(host: String, port: Port)` с `asUrl(): String`
  - `ServerState.Stopped`, `ServerState.Starting`, `ServerState.Running(address: ServerAddress?, pin: AccessPin)`
  - `TransferEvent.Progress(transferId, file, bytes, total, pct, etaSeconds)`, `TransferEvent.Done(transferId, files, bytes)`, `TransferEvent.Error(transferId, code, message)`

- [ ] **Step 1: Написать падающий тест FilePathTest**

Create `app/src/test/java/ru/kryu/ferryfile/domain/model/FilePathTest.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

import org.junit.Assert.*
import org.junit.Test

class FilePathTest {

    @Test fun `slash parses to root`() {
        val path = FilePath.parse("/")
        assertNotNull(path)
        assertTrue(path!!.isRoot)
        assertEquals(emptyList<String>(), path.segments)
    }

    @Test fun `root index only is a valid path`() {
        val path = FilePath.parse("0")!!
        assertFalse(path.isRoot)
        assertEquals(0, path.rootIndex)
        assertEquals(listOf("0"), path.segments)
        assertEquals("0", path.name)
    }

    @Test fun `nested path keeps segments and name`() {
        val path = FilePath.parse("1/docs/report.pdf")!!
        assertEquals(1, path.rootIndex)
        assertEquals(listOf("1", "docs", "report.pdf"), path.segments)
        assertEquals("report.pdf", path.name)
        assertEquals("1/docs/report.pdf", path.raw)
    }

    @Test fun `leading and trailing slashes are normalized away`() {
        assertEquals("0/docs", FilePath.parse("/0/docs/")!!.raw)
    }

    @Test fun `parent traversal is rejected`() {
        assertNull(FilePath.parse("0/../secret"))
        assertNull(FilePath.parse("0/.."))
    }

    @Test fun `current directory segment is rejected`() {
        assertNull(FilePath.parse("0/./docs"))
    }

    @Test fun `empty segment is rejected`() {
        assertNull(FilePath.parse("0//docs"))
    }

    @Test fun `non numeric root is rejected`() {
        assertNull(FilePath.parse("docs/report.pdf"))
    }

    @Test fun `negative root index is rejected`() {
        assertNull(FilePath.parse("-1/docs"))
    }

    @Test fun `null and blank are rejected`() {
        assertNull(FilePath.parse(null))
        assertNull(FilePath.parse(""))
        assertNull(FilePath.parse("   "))
    }

    @Test fun `root factory builds path for index`() {
        assertEquals("2", FilePath.root(2).raw)
    }

    @Test fun `child appends a segment`() {
        assertEquals("0/docs/a.txt", FilePath.parse("0/docs")!!.child("a.txt")!!.raw)
    }

    @Test fun `child rejects traversal and separators`() {
        val dir = FilePath.parse("0/docs")!!
        assertNull(dir.child(".."))
        assertNull(dir.child("."))
        assertNull(dir.child(""))
        assertNull(dir.child("sub/file.txt"))
    }

    @Test fun `root has no children`() {
        assertNull(FilePath.ROOT.child("0"))
    }
}
```

- [ ] **Step 2: Прогнать тест и убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.model.FilePathTest" --console=plain`
Expected: FAIL с ошибкой компиляции «Unresolved reference: FilePath»

- [ ] **Step 3: Реализовать FilePath**

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/FilePath.kt`:

```kotlin
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
        private const val ROOT_RAW = "/"
        private const val SEPARATOR = '/'

        val ROOT = FilePath(ROOT_RAW)

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
```

- [ ] **Step 4: Прогнать тест — должен пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.model.FilePathTest" --console=plain`
Expected: PASS, 14 тестов

- [ ] **Step 5: Написать падающие тесты PortTest и AccessPinTest**

Create `app/src/test/java/ru/kryu/ferryfile/domain/model/PortTest.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

import org.junit.Assert.*
import org.junit.Test

class PortTest {

    @Test fun `default port is 8080`() {
        assertEquals(8080, Port.DEFAULT.value)
    }

    @Test fun `port inside range is accepted`() {
        assertEquals(1024, Port.parse(1024)!!.value)
        assertEquals(65535, Port.parse(65535)!!.value)
    }

    @Test fun `port below range is rejected`() {
        assertNull(Port.parse(1023))
        assertNull(Port.parse(0))
        assertNull(Port.parse(-1))
    }

    @Test fun `port above range is rejected`() {
        assertNull(Port.parse(65536))
    }

    @Test fun `text is parsed when numeric and in range`() {
        assertEquals(9000, Port.parse("9000")!!.value)
    }

    @Test fun `non numeric text is rejected`() {
        assertNull(Port.parse("abc"))
        assertNull(Port.parse(""))
    }
}
```

Create `app/src/test/java/ru/kryu/ferryfile/domain/model/AccessPinTest.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessPinTest {

    @Test fun `six digits are accepted`() {
        assertEquals("012345", AccessPin.parse("012345")!!.digits)
    }

    @Test fun `wrong length is rejected`() {
        assertNull(AccessPin.parse("12345"))
        assertNull(AccessPin.parse("1234567"))
        assertNull(AccessPin.parse(""))
    }

    @Test fun `non digits are rejected`() {
        assertNull(AccessPin.parse("12a456"))
        assertNull(AccessPin.parse(" 12345"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `of throws on invalid pin`() {
        AccessPin.of("nope")
    }
}
```

- [ ] **Step 6: Прогнать — должны падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.model.*" --console=plain`
Expected: FAIL, «Unresolved reference: Port», «Unresolved reference: AccessPin»

- [ ] **Step 7: Реализовать остальные модели**

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/Port.kt`:

```kotlin
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
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/AccessPin.kt`:

```kotlin
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
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/FileNode.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

/** Файл или директория внутри расшаренной папки. */
data class FileNode(
    val path: FilePath,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String
)
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/SharedFolder.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

/** Папка, к которой пользователь выдал доступ через SAF. */
data class SharedFolder(val uri: String, val displayName: String)
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/ServerAddress.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

data class ServerAddress(val host: String, val port: Port) {
    fun asUrl(): String = "http://$host:${port.value}"
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/ServerState.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

sealed interface ServerState {

    data object Stopped : ServerState

    data object Starting : ServerState

    /** [address] равен `null`, когда сервер запущен, но устройство не в Wi-Fi-сети. */
    data class Running(val address: ServerAddress?, val pin: AccessPin) : ServerState
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/model/TransferEvent.kt`:

```kotlin
package ru.kryu.ferryfile.domain.model

/**
 * Событие передачи. [transferId] генерирует клиент, чтобы несколько вкладок браузера
 * не видели чужой прогресс. Сериализация — забота HTTP-слоя, здесь её нет.
 */
sealed interface TransferEvent {

    val transferId: String

    data class Progress(
        override val transferId: String,
        val file: String,
        val bytes: Long,
        val total: Long,
        val pct: Int,
        val etaSeconds: Int
    ) : TransferEvent

    data class Done(
        override val transferId: String,
        val files: Int,
        val bytes: Long
    ) : TransferEvent

    data class Error(
        override val transferId: String,
        val code: String,
        val message: String
    ) : TransferEvent
}
```

- [ ] **Step 8: Прогнать тесты моделей — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.model.*" --console=plain`
Expected: PASS

- [ ] **Step 9: Написать тест чистоты слоя domain**

Create `app/src/test/java/ru/kryu/ferryfile/domain/DomainLayerPurityTest.kt`:

```kotlin
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
```

- [ ] **Step 10: Прогнать тест чистоты — должен пройти сразу**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.DomainLayerPurityTest" --console=plain`
Expected: PASS. Если упал с «Каталог domain не найден» — значит рабочая директория теста не совпадает с каталогом модуля; заменить путь на `File("app/src/main/java/ru/kryu/ferryfile/domain")` и перепроверить.

- [ ] **Step 11: Прогнать весь набор тестов**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Коммит**

```bash
git add app/src/main/java/ru/kryu/ferryfile/domain app/src/test/java/ru/kryu/ferryfile/domain
git commit -m "feat(domain): add domain models and layer purity test"
```

---

### Task 3: SettingsRepository — интерфейс, реактивная реализация, биндинги

Переводит настройки на `StateFlow` целиком (сейчас реактивна только тема) и убирает `Context` из `SettingsViewModel`.

**Files:**
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/repository/SettingsRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/files/SafPermissionManager.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/settings/SettingsRepositoryImpl.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/di/RepositoryModule.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/MainActivity.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/KtorServer.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/saf/SafFileProvider.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/service/FileServerService.kt`
- Delete: `app/src/main/java/ru/kryu/ferryfile/data/PreferencesRepository.kt`
- Delete: `app/src/test/java/ru/kryu/ferryfile/data/PreferencesRepositoryTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/data/settings/SettingsRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `Port`, `SharedFolder` (Задача 2)
- Produces:
  - `SettingsRepository` со свойствами `port: StateFlow<Port>`, `darkTheme: StateFlow<Boolean>`, `sharedFolders: StateFlow<List<SharedFolder>>` и методами `suspend setPort(Port)`, `suspend setDarkTheme(Boolean)`, `suspend addSharedFolder(uri: String): Boolean`, `suspend removeSharedFolder(uri: String)`
  - `SafPermissionManager` с `takePersistable(uri: String): Boolean`, `release(uri: String)`, `displayName(uri: String): String`
  - `SettingsRepositoryImpl(prefs: SharedPreferences, permissions: SafPermissionManager)`
  - `RepositoryModule` — точка подключения всех будущих `@Binds`

- [ ] **Step 1: Написать падающий тест SettingsRepositoryImplTest**

Create `app/src/test/java/ru/kryu/ferryfile/data/settings/SettingsRepositoryImplTest.kt`:

```kotlin
package ru.kryu.ferryfile.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.data.files.SafPermissionManager
import ru.kryu.ferryfile.domain.model.Port

class SettingsRepositoryImplTest {

    private val prefs: SharedPreferences = mock()
    private val editor: SharedPreferences.Editor = mock()
    private val permissions: SafPermissionManager = mock()

    @Before fun setUp() {
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(editor.putInt(any(), any())).thenReturn(editor)
        whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        whenever(prefs.getString(eq("saf_uris"), any())).thenReturn("[]")
        whenever(prefs.getInt(eq("server_port"), any())).thenReturn(8080)
        whenever(prefs.getBoolean(eq("dark_theme"), any())).thenReturn(true)
        whenever(permissions.displayName(any())).thenAnswer { it.arguments[0].toString().substringAfterLast('/') }
        whenever(permissions.takePersistable(any())).thenReturn(true)
    }

    private fun repo() = SettingsRepositoryImpl(prefs, permissions)

    @Test fun `port falls back to default when stored value is out of range`() {
        whenever(prefs.getInt(eq("server_port"), any())).thenReturn(80)
        assertEquals(Port.DEFAULT, repo().port.value)
    }

    @Test fun `stored port is exposed`() {
        whenever(prefs.getInt(eq("server_port"), any())).thenReturn(9000)
        assertEquals(9000, repo().port.value.value)
    }

    @Test fun `setPort persists and updates the flow`() = runTest {
        val repo = repo()
        repo.setPort(Port.parse(9100)!!)
        verify(editor).putInt("server_port", 9100)
        assertEquals(9100, repo.port.value.value)
    }

    @Test fun `shared folders are decoded from stored json`() {
        whenever(prefs.getString(eq("saf_uris"), any()))
            .thenReturn(Json.encodeToString(listOf("content://tree/photos", "content://tree/docs")))
        val folders = repo().sharedFolders.value
        assertEquals(listOf("content://tree/photos", "content://tree/docs"), folders.map { it.uri })
        assertEquals(listOf("photos", "docs"), folders.map { it.displayName })
    }

    @Test fun `corrupted json yields no shared folders`() {
        whenever(prefs.getString(eq("saf_uris"), any())).thenReturn("{not valid json}")
        assertTrue(repo().sharedFolders.value.isEmpty())
    }

    @Test fun `addSharedFolder persists uri and updates the flow`() = runTest {
        val repo = repo()
        assertTrue(repo.addSharedFolder("content://tree/photos"))
        verify(editor).putString("saf_uris", Json.encodeToString(listOf("content://tree/photos")))
        assertEquals(listOf("content://tree/photos"), repo.sharedFolders.value.map { it.uri })
    }

    @Test fun `addSharedFolder returns false and stores nothing when permission is denied`() = runTest {
        whenever(permissions.takePersistable(any())).thenReturn(false)
        val repo = repo()
        assertFalse(repo.addSharedFolder("content://tree/photos"))
        verify(editor, never()).putString(eq("saf_uris"), any())
        assertTrue(repo.sharedFolders.value.isEmpty())
    }

    @Test fun `addSharedFolder does not duplicate an existing uri`() = runTest {
        whenever(prefs.getString(eq("saf_uris"), any()))
            .thenReturn(Json.encodeToString(listOf("content://tree/photos")))
        val repo = repo()
        assertTrue(repo.addSharedFolder("content://tree/photos"))
        assertEquals(1, repo.sharedFolders.value.size)
    }

    @Test fun `removeSharedFolder releases permission and updates the flow`() = runTest {
        whenever(prefs.getString(eq("saf_uris"), any()))
            .thenReturn(Json.encodeToString(listOf("content://tree/photos", "content://tree/docs")))
        val repo = repo()
        repo.removeSharedFolder("content://tree/photos")
        verify(permissions).release("content://tree/photos")
        assertEquals(listOf("content://tree/docs"), repo.sharedFolders.value.map { it.uri })
    }

    @Test fun `setDarkTheme persists and updates the flow`() = runTest {
        val repo = repo()
        repo.setDarkTheme(false)
        verify(editor).putBoolean("dark_theme", false)
        assertFalse(repo.darkTheme.value)
    }
}
```

- [ ] **Step 2: Прогнать — должен падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.settings.SettingsRepositoryImplTest" --console=plain`
Expected: FAIL, «Unresolved reference: SettingsRepositoryImpl»

- [ ] **Step 3: Создать интерфейс SettingsRepository**

Create `app/src/main/java/ru/kryu/ferryfile/domain/repository/SettingsRepository.kt`:

```kotlin
package ru.kryu.ferryfile.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder

interface SettingsRepository {

    val port: StateFlow<Port>

    val darkTheme: StateFlow<Boolean>

    val sharedFolders: StateFlow<List<SharedFolder>>

    suspend fun setPort(port: Port)

    suspend fun setDarkTheme(enabled: Boolean)

    /** @return `false`, если система не выдала постоянное разрешение на папку. */
    suspend fun addSharedFolder(uri: String): Boolean

    suspend fun removeSharedFolder(uri: String)
}
```

- [ ] **Step 4: Создать SafPermissionManager**

Create `app/src/main/java/ru/kryu/ferryfile/data/files/SafPermissionManager.kt`:

```kotlin
package ru.kryu.ferryfile.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Работа с постоянными разрешениями SAF и отображаемыми именами папок. */
@Singleton
class SafPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun takePersistable(uri: String): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uri), flags)
    }.isSuccess

    fun release(uri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags) }
    }

    fun displayName(uri: String): String =
        runCatching { Uri.decode(Uri.parse(uri).lastPathSegment) }.getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: uri
}
```

- [ ] **Step 5: Реализовать SettingsRepositoryImpl**

Create `app/src/main/java/ru/kryu/ferryfile/data/settings/SettingsRepositoryImpl.kt`:

```kotlin
package ru.kryu.ferryfile.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.kryu.ferryfile.data.files.SafPermissionManager
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences,
    private val permissions: SafPermissionManager
) : SettingsRepository {

    private val _port = MutableStateFlow(readPort())
    override val port: StateFlow<Port> = _port.asStateFlow()

    private val _darkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK_THEME, true))
    override val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _sharedFolders = MutableStateFlow(readUris().toFolders())
    override val sharedFolders: StateFlow<List<SharedFolder>> = _sharedFolders.asStateFlow()

    override suspend fun setPort(port: Port) {
        prefs.edit().putInt(KEY_PORT, port.value).apply()
        _port.value = port
    }

    override suspend fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        _darkTheme.value = enabled
    }

    override suspend fun addSharedFolder(uri: String): Boolean {
        val stored = readUris()
        if (uri in stored) return true
        if (!permissions.takePersistable(uri)) return false
        writeUris(stored + uri)
        return true
    }

    override suspend fun removeSharedFolder(uri: String) {
        permissions.release(uri)
        writeUris(readUris().filterNot { it == uri })
    }

    private fun readPort(): Port =
        Port.parse(prefs.getInt(KEY_PORT, Port.DEFAULT.value)) ?: Port.DEFAULT

    private fun readUris(): List<String> = runCatching {
        Json.decodeFromString<List<String>>(prefs.getString(KEY_SAF_URIS, EMPTY_JSON_ARRAY) ?: EMPTY_JSON_ARRAY)
    }.getOrDefault(emptyList())

    private fun writeUris(uris: List<String>) {
        prefs.edit().putString(KEY_SAF_URIS, Json.encodeToString(uris)).apply()
        _sharedFolders.value = uris.toFolders()
    }

    private fun List<String>.toFolders(): List<SharedFolder> =
        map { SharedFolder(it, permissions.displayName(it)) }

    private companion object {
        const val KEY_PORT = "server_port"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_SAF_URIS = "saf_uris"
        const val EMPTY_JSON_ARRAY = "[]"
    }
}
```

- [ ] **Step 6: Создать модуль биндингов**

Create `app/src/main/java/ru/kryu/ferryfile/data/di/RepositoryModule.kt`:

```kotlin
package ru.kryu.ferryfile.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.kryu.ferryfile.data.settings.SettingsRepositoryImpl
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
```

- [ ] **Step 7: Перевести потребителей на SettingsRepository и удалить PreferencesRepository**

В `MainActivity.kt` заменить поле и обращение:

```kotlin
    @Inject lateinit var settings: SettingsRepository
```
```kotlin
            val darkTheme by settings.darkTheme.collectAsStateWithLifecycle()
```

В `SettingsViewModel.kt` — временно (до Задачи 5) заменить `PreferencesRepository` на `SettingsRepository`, убрать `@ApplicationContext context`, а `addSafUri`/`removeSafUri` перевести на `viewModelScope.launch { … }`; состояние собрать из потоков:

```kotlin
data class SettingsUiState(
    val port: Int = 8080,
    val darkTheme: Boolean = true,
    val sharedFolders: List<SharedFolder> = emptyList()
)
```

```kotlin
    val uiState: StateFlow<SettingsUiState> = combine(
        settings.port, settings.darkTheme, settings.sharedFolders
    ) { port, dark, folders ->
        SettingsUiState(port = port.value, darkTheme = dark, sharedFolders = folders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
```

Пароль уходит из слоя UI уже здесь, иначе `combine` пришлось бы кормить значением из ещё не созданного `uiState`. Конкретно: из `SettingsUiState` убирается `hasPassword`, из `SettingsScreen` — вся секция Password (статус, поле ввода, кнопки Set/Clear), из `HomeUiState` — `hasPassword`, а из `HomeScreen` — карточка-предупреждение и `enabled = uiState.hasPassword` у кнопки Start. Хранимый хеш до Задачи 8 читает только `KtorServer` напрямую из `SharedPreferences`, поэтому уже настроенный пароль продолжает работать; на чистой установке вход в веб-интерфейс между Задачами 3 и 8 недоступен — это промежуточное состояние, которое снимает Задача 8.

`SettingsScreen` правится под новое поле `sharedFolders: List<SharedFolder>` (отображать `folder.displayName`, передавать `folder.uri` в remove).

В `HomeViewModel.kt`, `KtorServer.kt`, `SafFileProvider.kt`, `FileServerService.kt` заменить тип инжектируемой зависимости `PreferencesRepository` на `SettingsRepository`, а обращения: `prefs.port` → `settings.port.value.value`, `prefs.safUris` → `settings.sharedFolders.value.map { it.uri }`, `prefs.passwordHash` оставить временно — перенести чтение хеша прямо в `KtorServer` через `SharedPreferences`, поскольку из репозитория пароль ушёл:

```kotlin
// KtorServer: до Задачи 8 пароль читается напрямую из SharedPreferences
private val passwordHash: String get() = prefs.getString("password_hash", "") ?: ""
```

Затем удалить файлы:

```bash
git rm app/src/main/java/ru/kryu/ferryfile/data/PreferencesRepository.kt \
       app/src/test/java/ru/kryu/ferryfile/data/PreferencesRepositoryTest.kt
```

- [ ] **Step 8: Прогнать все тесты**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS, включая новый `SettingsRepositoryImplTest` (10 тестов)

- [ ] **Step 9: Проверить, что приложение собирается**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Коммит**

```bash
git add -A
git commit -m "refactor(data): introduce SettingsRepository interface with reactive state"
```

---

### Task 4: FileStorageRepository поверх SAF

`SafFileProvider` распадается на три части: `SafRootsProvider` (список корней), `SafPathResolver` (обход сегментов) и `SafFileStorageRepository` (реализация доменного интерфейса). Разбор пути уже живёт в `FilePath`, поэтому `isValidPath` исчезает как понятие.

**Files:**
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/repository/FileStorageRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/files/SafRootsProvider.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/files/SafPathResolver.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/files/SafFileStorageRepository.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/data/di/RepositoryModule.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/data/files/SafPathResolverTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/data/files/SafFileStorageRepositoryTest.kt`

**Interfaces:**
- Consumes: `FilePath`, `FileNode` (Задача 2), `SettingsRepository` (Задача 3)
- Produces:
  - `FileStorageRepository` с `suspend listRoot(): List<FileNode>`, `suspend list(path: FilePath): List<FileNode>?`, `suspend node(path: FilePath): FileNode?`, `suspend read(path: FilePath): InputStream?`, `suspend createFile(dir: FilePath, name: String, mimeType: String): FilePath?`, `suspend write(path: FilePath): OutputStream?`
  - `SafRootsProvider.roots(): List<DocumentFile>`
  - `SafPathResolver.resolve(path: FilePath): DocumentFile?`

- [ ] **Step 1: Написать падающий SafPathResolverTest**

Create `app/src/test/java/ru/kryu/ferryfile/data/files/SafPathResolverTest.kt`:

```kotlin
package ru.kryu.ferryfile.data.files

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.domain.model.FilePath

class SafPathResolverTest {

    private val rootsProvider: SafRootsProvider = mock()
    private val resolver = SafPathResolver(rootsProvider)

    private fun dir(): DocumentFile = mock<DocumentFile>().also { whenever(it.isDirectory).thenReturn(true) }

    @Test fun `virtual root resolves to nothing`() {
        assertNull(resolver.resolve(FilePath.ROOT))
    }

    @Test fun `first segment selects the shared folder by index`() {
        val first = dir()
        val second = dir()
        whenever(rootsProvider.roots()).thenReturn(listOf(first, second))
        assertSame(second, resolver.resolve(FilePath.parse("1")!!))
    }

    @Test fun `unknown root index resolves to null`() {
        whenever(rootsProvider.roots()).thenReturn(listOf(dir()))
        assertNull(resolver.resolve(FilePath.parse("7")!!))
    }

    @Test fun `nested segments are walked in order`() {
        val root = dir()
        val docs = dir()
        val file: DocumentFile = mock()
        whenever(rootsProvider.roots()).thenReturn(listOf(root))
        whenever(root.findFile("docs")).thenReturn(docs)
        whenever(docs.findFile("report.pdf")).thenReturn(file)

        assertSame(file, resolver.resolve(FilePath.parse("0/docs/report.pdf")!!))
    }

    @Test fun `missing segment resolves to null`() {
        val root = dir()
        whenever(rootsProvider.roots()).thenReturn(listOf(root))
        whenever(root.findFile("ghost")).thenReturn(null)
        assertNull(resolver.resolve(FilePath.parse("0/ghost/deeper")!!))
    }
}
```

- [ ] **Step 2: Прогнать — должен падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.files.SafPathResolverTest" --console=plain`
Expected: FAIL, «Unresolved reference: SafPathResolver»

- [ ] **Step 3: Создать доменный интерфейс хранилища**

Create `app/src/main/java/ru/kryu/ferryfile/domain/repository/FileStorageRepository.kt`:

```kotlin
package ru.kryu.ferryfile.domain.repository

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import java.io.InputStream
import java.io.OutputStream

interface FileStorageRepository {

    /** Расшаренные папки как список узлов верхнего уровня. */
    suspend fun listRoot(): List<FileNode>

    /** Содержимое директории; `null`, если путь не найден или это не директория. */
    suspend fun list(path: FilePath): List<FileNode>?

    suspend fun node(path: FilePath): FileNode?

    /** Поток на чтение файла; `null` для директории или отсутствующего пути. */
    suspend fun read(path: FilePath): InputStream?

    /** @return путь созданного файла — имя может отличаться от запрошенного при конфликте. */
    suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath?

    suspend fun write(path: FilePath): OutputStream?
}
```

- [ ] **Step 4: Реализовать SafRootsProvider и SafPathResolver**

Create `app/src/main/java/ru/kryu/ferryfile/data/files/SafRootsProvider.kt`:

```kotlin
package ru.kryu.ferryfile.data.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Расшаренные папки в том же порядке, в каком они хранятся в настройках: индекс = корень пути. */
@Singleton
class SafRootsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {

    fun roots(): List<DocumentFile> = settings.sharedFolders.value.mapNotNull { folder ->
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folder.uri)) }.getOrNull()
    }
}
```

Create `app/src/main/java/ru/kryu/ferryfile/data/files/SafPathResolver.kt`:

```kotlin
package ru.kryu.ferryfile.data.files

import androidx.documentfile.provider.DocumentFile
import ru.kryu.ferryfile.domain.model.FilePath
import javax.inject.Inject
import javax.inject.Singleton

/** Превращает [FilePath] в [DocumentFile]. Валидация самого пути уже выполнена в [FilePath.parse]. */
@Singleton
class SafPathResolver @Inject constructor(
    private val roots: SafRootsProvider
) {

    fun resolve(path: FilePath): DocumentFile? {
        if (path.isRoot) return null
        val index = path.rootIndex ?: return null
        var current = roots.roots().getOrNull(index) ?: return null
        for (segment in path.segments.drop(1)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}
```

- [ ] **Step 5: Прогнать SafPathResolverTest — должен пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.files.SafPathResolverTest" --console=plain`
Expected: PASS, 5 тестов

- [ ] **Step 6: Написать падающий SafFileStorageRepositoryTest**

Create `app/src/test/java/ru/kryu/ferryfile/data/files/SafFileStorageRepositoryTest.kt`:

```kotlin
package ru.kryu.ferryfile.data.files

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import ru.kryu.ferryfile.domain.model.FilePath

class SafFileStorageRepositoryTest {

    private val context: Context = mock()
    private val rootsProvider: SafRootsProvider = mock()
    private val resolver: SafPathResolver = mock()
    private val repo = SafFileStorageRepository(context, rootsProvider, resolver)

    private fun folder(name: String): DocumentFile = mock<DocumentFile>().also {
        whenever(it.isDirectory).thenReturn(true)
        whenever(it.name).thenReturn(name)
        whenever(it.lastModified()).thenReturn(1000L)
    }

    private fun file(name: String, size: Long, mime: String = "text/plain"): DocumentFile =
        mock<DocumentFile>().also {
            whenever(it.isDirectory).thenReturn(false)
            whenever(it.name).thenReturn(name)
            whenever(it.length()).thenReturn(size)
            whenever(it.lastModified()).thenReturn(2000L)
            whenever(it.type).thenReturn(mime)
        }

    @Test fun `listRoot maps shared folders to indexed paths`() = runTest {
        whenever(rootsProvider.roots()).thenReturn(listOf(folder("Photos"), folder("Docs")))

        val nodes = repo.listRoot()

        assertEquals(listOf("0", "1"), nodes.map { it.path.raw })
        assertEquals(listOf("Photos", "Docs"), nodes.map { it.name })
        assertTrue(nodes.all { it.isDirectory })
    }

    @Test fun `list maps children to nested paths`() = runTest {
        val dir = folder("docs")
        whenever(dir.listFiles()).thenReturn(arrayOf(file("report.pdf", 42L), folder("sub")))
        whenever(resolver.resolve(FilePath.parse("0/docs")!!)).thenReturn(dir)

        val nodes = repo.list(FilePath.parse("0/docs")!!)!!

        assertEquals(listOf("0/docs/report.pdf", "0/docs/sub"), nodes.map { it.path.raw })
        assertEquals(42L, nodes.first().sizeBytes)
        assertEquals("text/plain", nodes.first().mimeType)
    }

    @Test fun `list returns null for a file`() = runTest {
        whenever(resolver.resolve(any())).thenReturn(file("report.pdf", 1L))
        assertNull(repo.list(FilePath.parse("0/report.pdf")!!))
    }

    @Test fun `list returns null for unknown path`() = runTest {
        whenever(resolver.resolve(any())).thenReturn(null)
        assertNull(repo.list(FilePath.parse("0/ghost")!!))
    }

    @Test fun `node describes a single file`() = runTest {
        whenever(resolver.resolve(any())).thenReturn(file("report.pdf", 7L, "application/pdf"))

        val node = repo.node(FilePath.parse("0/report.pdf")!!)!!

        assertEquals("report.pdf", node.name)
        assertEquals(7L, node.sizeBytes)
        assertEquals("application/pdf", node.mimeType)
        assertFalse(node.isDirectory)
    }

    @Test fun `createFile returns the path actually created by the provider`() = runTest {
        val dir = folder("docs")
        whenever(resolver.resolve(FilePath.parse("0/docs")!!)).thenReturn(dir)
        whenever(dir.createFile("text/plain", "notes.txt")).thenReturn(file("notes (1).txt", 0L))

        val created = repo.createFile(FilePath.parse("0/docs")!!, "notes.txt", "text/plain")

        assertEquals("0/docs/notes (1).txt", created!!.raw)
    }

    @Test fun `createFile returns null when destination is not a directory`() = runTest {
        whenever(resolver.resolve(any())).thenReturn(file("report.pdf", 1L))
        assertNull(repo.createFile(FilePath.parse("0/report.pdf")!!, "notes.txt", "text/plain"))
    }
}
```

- [ ] **Step 7: Прогнать — должен падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.files.SafFileStorageRepositoryTest" --console=plain`
Expected: FAIL, «Unresolved reference: SafFileStorageRepository»

- [ ] **Step 8: Реализовать SafFileStorageRepository**

Create `app/src/main/java/ru/kryu/ferryfile/data/files/SafFileStorageRepository.kt`:

```kotlin
package ru.kryu.ferryfile.data.files

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafFileStorageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roots: SafRootsProvider,
    private val resolver: SafPathResolver
) : FileStorageRepository {

    override suspend fun listRoot(): List<FileNode> = withContext(Dispatchers.IO) {
        roots.roots().mapIndexed { index, root ->
            FileNode(
                path = FilePath.root(index),
                name = root.name ?: "Folder ${index + 1}",
                sizeBytes = 0L,
                lastModified = root.lastModified(),
                isDirectory = true,
                mimeType = MIME_DIRECTORY
            )
        }
    }

    override suspend fun list(path: FilePath): List<FileNode>? = withContext(Dispatchers.IO) {
        val dir = resolver.resolve(path)?.takeIf { it.isDirectory } ?: return@withContext null
        dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val childPath = path.child(name) ?: return@mapNotNull null
            child.toNode(childPath, name)
        }
    }

    override suspend fun node(path: FilePath): FileNode? = withContext(Dispatchers.IO) {
        val file = resolver.resolve(path) ?: return@withContext null
        file.toNode(path, file.name ?: path.name)
    }

    override suspend fun read(path: FilePath): InputStream? = withContext(Dispatchers.IO) {
        val file = resolver.resolve(path)?.takeIf { !it.isDirectory } ?: return@withContext null
        runCatching { context.contentResolver.openInputStream(file.uri) }.getOrNull()
    }

    override suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath? =
        withContext(Dispatchers.IO) {
            val parent = resolver.resolve(dir)?.takeIf { it.isDirectory } ?: return@withContext null
            val created = parent.createFile(mimeType, name) ?: return@withContext null
            dir.child(created.name ?: name)
        }

    override suspend fun write(path: FilePath): OutputStream? = withContext(Dispatchers.IO) {
        val file = resolver.resolve(path)?.takeIf { !it.isDirectory } ?: return@withContext null
        runCatching { context.contentResolver.openOutputStream(file.uri) }.getOrNull()
    }

    private fun DocumentFile.toNode(path: FilePath, name: String) = FileNode(
        path = path,
        name = name,
        sizeBytes = if (isDirectory) 0L else length(),
        lastModified = lastModified(),
        isDirectory = isDirectory,
        mimeType = type ?: if (isDirectory) MIME_DIRECTORY else MIME_BINARY
    )

    private companion object {
        const val MIME_DIRECTORY = "vnd.android.document/directory"
        const val MIME_BINARY = "application/octet-stream"
    }
}
```

- [ ] **Step 9: Добавить биндинг**

В `app/src/main/java/ru/kryu/ferryfile/data/di/RepositoryModule.kt` добавить импорты и метод:

```kotlin
    @Binds
    @Singleton
    abstract fun bindFileStorageRepository(impl: SafFileStorageRepository): FileStorageRepository
```

- [ ] **Step 10: Прогнать тесты**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS (`SafFileStorageRepositoryTest` — 7 тестов)

- [ ] **Step 11: Коммит**

```bash
git add app/src/main/java/ru/kryu/ferryfile/domain app/src/main/java/ru/kryu/ferryfile/data app/src/test/java/ru/kryu/ferryfile/data
git commit -m "feat(data): add SAF-backed FileStorageRepository with path resolver"
```

---

### Task 5: Use cases, сеть, PIN-хранилище, состояние сервера, ViewModel'и

Самая крупная задача: появляется слой use cases, `HomeViewModel` перестаёт знать про `ConnectivityManager`, zxing и `KtorServer`, а `SafFileProvider` удаляется.

**Files:**
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/repository/NetworkRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/repository/AccessCodeRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/repository/ServerRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/usecase/ListDirectoryUseCase.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/usecase/SaveUploadUseCase.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/usecase/DownloadSelectionUseCase.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/usecase/VerifyAccessCodeUseCase.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/usecase/ServerUseCases.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/domain/usecase/SettingsUseCases.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/network/WifiNetworkRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/server/InMemoryAccessCodeRepository.kt`
- Create: `app/src/main/java/ru/kryu/ferryfile/data/server/ServerRepositoryImpl.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/data/di/RepositoryModule.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeViewModel.kt` (переписывается целиком)
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt` (убрать блок QR)
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/MainActivity.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml` (удалить zxing)
- Delete: `app/src/main/java/ru/kryu/ferryfile/server/saf/SafFileProvider.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/FakeFileStorageRepository.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/usecase/SaveUploadUseCaseTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/domain/usecase/DownloadSelectionUseCaseTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/data/server/InMemoryAccessCodeRepositoryTest.kt`

**Interfaces:**
- Consumes: всё из Задач 2–4
- Produces:
  - `NetworkRepository.localAddress(): String?` (suspend)
  - `AccessCodeRepository` с `current: AccessPin?`, `issue(): AccessPin`, `revoke()`, `verify(candidate: String): Boolean`
  - `ServerRepository` с `state: StateFlow<ServerState>`, `suspend start()`, `suspend stop()`, `suspend refresh()`
  - `ListDirectoryUseCase.invoke(path: FilePath): List<FileNode>?`
  - `SaveUploadUseCase.invoke(dir, fileName, mimeType, source: InputStream, onBytesWritten: (Long) -> Unit): SaveUploadUseCase.Result`, где `Result.Saved(path: FilePath, bytesWritten: Long)`, `Result.RootNotWritable`, `Result.Failed`
  - `DownloadSelectionUseCase.resolve(paths: List<FilePath>): Selection` и `open(path: FilePath): InputStream?`, где `Selection.SingleFile(node: FileNode)`, `Selection.Archive(fileName: String, entries: List<ZipEntrySource>)`, `Selection.NotFound`; `ZipEntrySource(entryName: String, path: FilePath)`
  - `VerifyAccessCodeUseCase.invoke(candidate: String): Boolean`
  - `StartServerUseCase`, `StopServerUseCase`, `RefreshServerStateUseCase`, `ObserveServerStateUseCase`
  - `SetPortUseCase.invoke(text: String): SetPortUseCase.Result` (`Saved` / `OutOfRange`), `SetDarkThemeUseCase`, `AddSharedFolderUseCase`, `RemoveSharedFolderUseCase`, `ObserveSharedFoldersUseCase`, `ObservePortUseCase`, `ObserveDarkThemeUseCase`

- [ ] **Step 1: Написать фейковое хранилище для тестов use cases**

Create `app/src/test/java/ru/kryu/ferryfile/domain/FakeFileStorageRepository.kt`:

```kotlin
package ru.kryu.ferryfile.domain

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Хранилище в памяти: дерево задаётся через [addDirectory] и [addFile]. */
class FakeFileStorageRepository : FileStorageRepository {

    private val nodes = LinkedHashMap<String, FileNode>()
    private val fileContents = LinkedHashMap<String, ByteArray>()

    /** Содержимое, записанное через [write], по сырому пути. */
    val writtenFiles = LinkedHashMap<String, ByteArrayOutputStream>()

    var createFileFails = false

    fun addDirectory(raw: String): FileNode = put(raw, isDirectory = true, content = null)

    fun addFile(raw: String, content: String = "", mimeType: String = "text/plain"): FileNode {
        val node = put(raw, isDirectory = false, content = content.toByteArray(), mimeType = mimeType)
        return node
    }

    private fun put(
        raw: String,
        isDirectory: Boolean,
        content: ByteArray?,
        mimeType: String = "application/octet-stream"
    ): FileNode {
        val path = requireNotNull(FilePath.parse(raw)) { "Invalid test path: $raw" }
        val node = FileNode(
            path = path,
            name = path.name,
            sizeBytes = content?.size?.toLong() ?: 0L,
            lastModified = 0L,
            isDirectory = isDirectory,
            mimeType = if (isDirectory) "vnd.android.document/directory" else mimeType
        )
        nodes[path.raw] = node
        if (content != null) fileContents[path.raw] = content
        return node
    }

    override suspend fun listRoot(): List<FileNode> =
        nodes.values.filter { it.path.segments.size == 1 }

    override suspend fun list(path: FilePath): List<FileNode>? {
        val dir = nodes[path.raw] ?: return null
        if (!dir.isDirectory) return null
        val depth = path.segments.size
        return nodes.values.filter {
            it.path.segments.size == depth + 1 && it.path.raw.startsWith("${path.raw}/")
        }
    }

    override suspend fun node(path: FilePath): FileNode? = nodes[path.raw]

    override suspend fun read(path: FilePath): InputStream? =
        fileContents[path.raw]?.let { ByteArrayInputStream(it) }

    override suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath? {
        if (createFileFails) return null
        val parent = nodes[dir.raw] ?: return null
        if (!parent.isDirectory) return null
        val created = dir.child(name) ?: return null
        put(created.raw, isDirectory = false, content = ByteArray(0), mimeType = mimeType)
        return created
    }

    override suspend fun write(path: FilePath): OutputStream? {
        if (nodes[path.raw] == null) return null
        return ByteArrayOutputStream().also { writtenFiles[path.raw] = it }
    }
}
```

- [ ] **Step 2: Написать падающий SaveUploadUseCaseTest**

Create `app/src/test/java/ru/kryu/ferryfile/domain/usecase/SaveUploadUseCaseTest.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.model.FilePath

class SaveUploadUseCaseTest {

    private val storage = FakeFileStorageRepository()
    private val useCase = SaveUploadUseCase(storage)

    private fun docs(): FilePath {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        return FilePath.parse("0/docs")!!
    }

    @Test fun `writes the uploaded bytes into the destination folder`() = runTest {
        val dir = docs()
        val payload = "hello ferry"

        val result = useCase(dir, "notes.txt", "text/plain", payload.byteInputStream()) {}

        assertTrue(result is SaveUploadUseCase.Result.Saved)
        result as SaveUploadUseCase.Result.Saved
        assertEquals("0/docs/notes.txt", result.path.raw)
        assertEquals(payload.length.toLong(), result.bytesWritten)
        assertEquals(payload, storage.writtenFiles["0/docs/notes.txt"]!!.toString(Charsets.UTF_8.name()))
    }

    @Test fun `reports cumulative progress while copying`() = runTest {
        val dir = docs()
        val payload = ByteArray(20_000) { 'x'.code.toByte() }
        val reported = mutableListOf<Long>()

        useCase(dir, "big.bin", "application/octet-stream", payload.inputStream()) { reported += it }

        assertTrue(reported.isNotEmpty())
        assertEquals(20_000L, reported.last())
        assertEquals(reported.sorted(), reported)
    }

    @Test fun `refuses to write into the virtual root`() = runTest {
        val result = useCase(FilePath.ROOT, "notes.txt", "text/plain", "x".byteInputStream()) {}
        assertEquals(SaveUploadUseCase.Result.RootNotWritable, result)
        assertTrue(storage.writtenFiles.isEmpty())
    }

    @Test fun `fails when the file cannot be created`() = runTest {
        val dir = docs()
        storage.createFileFails = true

        val result = useCase(dir, "notes.txt", "text/plain", "x".byteInputStream()) {}

        assertEquals(SaveUploadUseCase.Result.Failed, result)
    }

    @Test fun `strips directory components from the client supplied name`() = runTest {
        val dir = docs()

        val result = useCase(dir, "../../etc/passwd", "text/plain", "x".byteInputStream()) {}

        assertTrue(result is SaveUploadUseCase.Result.Saved)
        assertEquals("0/docs/passwd", (result as SaveUploadUseCase.Result.Saved).path.raw)
    }

    @Test fun `falls back to a safe name when the client sends only separators`() = runTest {
        val dir = docs()

        val result = useCase(dir, "..", "text/plain", "x".byteInputStream()) {}

        assertEquals("0/docs/upload", (result as SaveUploadUseCase.Result.Saved).path.raw)
    }
}
```

- [ ] **Step 3: Написать падающий DownloadSelectionUseCaseTest**

Create `app/src/test/java/ru/kryu/ferryfile/domain/usecase/DownloadSelectionUseCaseTest.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.model.FilePath

class DownloadSelectionUseCaseTest {

    private val storage = FakeFileStorageRepository()
    private val useCase = DownloadSelectionUseCase(storage)

    private fun path(raw: String) = FilePath.parse(raw)!!

    @Before fun setUp() {
        storage.addDirectory("0")
        storage.addFile("0/report.pdf", "pdf-bytes")
        storage.addFile("0/photo.jpg", "jpg-bytes")
        storage.addDirectory("0/docs")
        storage.addFile("0/docs/a.txt", "a")
        storage.addDirectory("0/docs/sub")
        storage.addFile("0/docs/sub/b.txt", "b")
    }

    @Test fun `single file is served directly without an archive`() = runTest {
        val selection = useCase.resolve(listOf(path("0/report.pdf")))

        assertTrue(selection is DownloadSelectionUseCase.Selection.SingleFile)
        assertEquals("report.pdf", (selection as DownloadSelectionUseCase.Selection.SingleFile).node.name)
    }

    @Test fun `single folder becomes an archive named after the folder`() = runTest {
        val selection = useCase.resolve(listOf(path("0/docs")))

        selection as DownloadSelectionUseCase.Selection.Archive
        assertEquals("docs.zip", selection.fileName)
        assertEquals(
            listOf("docs/a.txt", "docs/sub/b.txt"),
            selection.entries.map { it.entryName }.sorted()
        )
    }

    @Test fun `multiple paths become a selection archive`() = runTest {
        val selection = useCase.resolve(listOf(path("0/report.pdf"), path("0/photo.jpg")))

        selection as DownloadSelectionUseCase.Selection.Archive
        assertEquals("ferryfile-selection.zip", selection.fileName)
        assertEquals(listOf("report.pdf", "photo.jpg"), selection.entries.map { it.entryName })
    }

    @Test fun `mixed selection keeps folder structure inside the archive`() = runTest {
        val selection = useCase.resolve(listOf(path("0/report.pdf"), path("0/docs")))

        selection as DownloadSelectionUseCase.Selection.Archive
        assertEquals("ferryfile-selection.zip", selection.fileName)
        assertEquals(
            listOf("docs/a.txt", "docs/sub/b.txt", "report.pdf"),
            selection.entries.map { it.entryName }.sorted()
        )
    }

    @Test fun `unknown path yields NotFound`() = runTest {
        assertEquals(
            DownloadSelectionUseCase.Selection.NotFound,
            useCase.resolve(listOf(path("0/ghost")))
        )
    }

    @Test fun `empty selection yields NotFound`() = runTest {
        assertEquals(DownloadSelectionUseCase.Selection.NotFound, useCase.resolve(emptyList()))
    }

    @Test fun `virtual root cannot be downloaded`() = runTest {
        assertEquals(
            DownloadSelectionUseCase.Selection.NotFound,
            useCase.resolve(listOf(FilePath.ROOT))
        )
    }

    @Test fun `open returns the file content`() = runTest {
        assertEquals("pdf-bytes", useCase.open(path("0/report.pdf"))!!.readBytes().toString(Charsets.UTF_8))
    }
}
```

- [ ] **Step 4: Прогнать оба теста — должны падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.usecase.*" --console=plain`
Expected: FAIL, «Unresolved reference: SaveUploadUseCase», «Unresolved reference: DownloadSelectionUseCase»

- [ ] **Step 5: Реализовать use cases работы с файлами**

Create `app/src/main/java/ru/kryu/ferryfile/domain/usecase/ListDirectoryUseCase.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import javax.inject.Inject

class ListDirectoryUseCase @Inject constructor(
    private val storage: FileStorageRepository
) {

    suspend operator fun invoke(path: FilePath): List<FileNode>? =
        if (path.isRoot) storage.listRoot() else storage.list(path)
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/usecase/SaveUploadUseCase.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.InputStream
import javax.inject.Inject

/** Сохраняет один загруженный файл в расшаренную папку, сообщая накопленный объём. */
class SaveUploadUseCase @Inject constructor(
    private val storage: FileStorageRepository
) {

    sealed interface Result {
        data class Saved(val path: FilePath, val bytesWritten: Long) : Result

        /** Корень — виртуальный список расшаренных папок, писать в него некуда. */
        data object RootNotWritable : Result

        data object Failed : Result
    }

    suspend operator fun invoke(
        dir: FilePath,
        fileName: String,
        mimeType: String,
        source: InputStream,
        onBytesWritten: (Long) -> Unit
    ): Result {
        if (dir.isRoot) return Result.RootNotWritable

        val created = storage.createFile(dir, safeName(fileName), mimeType) ?: return Result.Failed
        val sink = storage.write(created) ?: return Result.Failed

        var total = 0L
        sink.use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                total += read
                onBytesWritten(total)
            }
            out.flush()
        }
        return Result.Saved(created, total)
    }

    /** Имя приходит от клиента, поэтому от него остаётся только последний сегмент. */
    private fun safeName(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: FALLBACK_NAME

    private companion object {
        const val FALLBACK_NAME = "upload"
    }
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/usecase/DownloadSelectionUseCase.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.model.FileNode
import ru.kryu.ferryfile.domain.model.FilePath
import ru.kryu.ferryfile.domain.repository.FileStorageRepository
import java.io.InputStream
import javax.inject.Inject

/**
 * Решает, чем именно является скачивание: одиночным файлом или архивом.
 * Одна папка — архив с её именем, несколько путей — общий архив выборки.
 */
class DownloadSelectionUseCase @Inject constructor(
    private val storage: FileStorageRepository
) {

    data class ZipEntrySource(val entryName: String, val path: FilePath)

    sealed interface Selection {
        data class SingleFile(val node: FileNode) : Selection
        data class Archive(val fileName: String, val entries: List<ZipEntrySource>) : Selection
        data object NotFound : Selection
    }

    suspend fun resolve(paths: List<FilePath>): Selection {
        if (paths.isEmpty() || paths.any { it.isRoot }) return Selection.NotFound

        val nodes = paths.map { storage.node(it) ?: return Selection.NotFound }

        if (nodes.size == 1) {
            val only = nodes.single()
            return if (only.isDirectory) {
                Selection.Archive("${only.name}.zip", collect(only, only.name))
            } else {
                Selection.SingleFile(only)
            }
        }

        return Selection.Archive(SELECTION_ARCHIVE_NAME, nodes.flatMap { collect(it, it.name) })
    }

    suspend fun open(path: FilePath): InputStream? = storage.read(path)

    private suspend fun collect(node: FileNode, entryName: String): List<ZipEntrySource> {
        if (!node.isDirectory) return listOf(ZipEntrySource(entryName, node.path))
        val children = storage.list(node.path) ?: return emptyList()
        return children.flatMap { collect(it, "$entryName/${it.name}") }
    }

    private companion object {
        const val SELECTION_ARCHIVE_NAME = "ferryfile-selection.zip"
    }
}
```

- [ ] **Step 6: Прогнать тесты use cases — должны пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.domain.usecase.*" --console=plain`
Expected: PASS (6 + 8 тестов)

- [ ] **Step 7: Написать падающий InMemoryAccessCodeRepositoryTest**

Create `app/src/test/java/ru/kryu/ferryfile/data/server/InMemoryAccessCodeRepositoryTest.kt`:

```kotlin
package ru.kryu.ferryfile.data.server

import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.model.AccessPin

class InMemoryAccessCodeRepositoryTest {

    private val repo = InMemoryAccessCodeRepository()

    @Test fun `no pin is issued before the server starts`() {
        assertNull(repo.current)
        assertFalse(repo.verify("000000"))
    }

    @Test fun `issued pin has six digits`() {
        val pin = repo.issue()
        assertEquals(AccessPin.LENGTH, pin.digits.length)
        assertTrue(pin.digits.all { it in '0'..'9' })
        assertEquals(pin, repo.current)
    }

    @Test fun `issued pin verifies and a wrong one does not`() {
        val pin = repo.issue()
        assertTrue(repo.verify(pin.digits))
        assertFalse(repo.verify("999999".takeIf { it != pin.digits } ?: "111111"))
    }

    @Test fun `revoked pin stops verifying`() {
        val pin = repo.issue()
        repo.revoke()
        assertNull(repo.current)
        assertFalse(repo.verify(pin.digits))
    }

    @Test fun `reissue replaces the previous pin`() {
        val first = repo.issue()
        repeat(20) { repo.issue() }
        assertNotEquals(first, repo.current)
        assertFalse(repo.verify(first.digits))
    }

    @Test fun `verify tolerates malformed input`() {
        repo.issue()
        assertFalse(repo.verify(""))
        assertFalse(repo.verify("abc"))
        assertFalse(repo.verify("0000000000"))
    }
}
```

- [ ] **Step 8: Реализовать репозитории data-слоя**

Create `app/src/main/java/ru/kryu/ferryfile/domain/repository/NetworkRepository.kt`:

```kotlin
package ru.kryu.ferryfile.domain.repository

interface NetworkRepository {

    /** IPv4-адрес устройства в локальной сети или `null`, если сети нет. */
    suspend fun localAddress(): String?
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/repository/AccessCodeRepository.kt`:

```kotlin
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
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/repository/ServerRepository.kt`:

```kotlin
package ru.kryu.ferryfile.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.ServerState

interface ServerRepository {

    val state: StateFlow<ServerState>

    suspend fun start()

    suspend fun stop()

    /** Пересобирает состояние по факту: сервер могли остановить из уведомления. */
    suspend fun refresh()
}
```

Create `app/src/main/java/ru/kryu/ferryfile/data/network/WifiNetworkRepository.kt`:

```kotlin
package ru.kryu.ferryfile.data.network

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kryu.ferryfile.domain.repository.NetworkRepository
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiNetworkRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkRepository {

    override suspend fun localAddress(): String? = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext null
        val network = manager.activeNetwork ?: return@withContext null
        val properties = manager.getLinkProperties(network) ?: return@withContext null
        properties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
```

Примечание: ветка для API ниже 31 из старого `HomeViewModel` не переносится — `minSdk` равен 30, но `ConnectivityManager.getLinkProperties` доступен с API 21, так что отдельный путь через устаревший `WifiManager` не нужен.

Create `app/src/main/java/ru/kryu/ferryfile/data/server/InMemoryAccessCodeRepository.kt`:

```kotlin
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
```

Create `app/src/main/java/ru/kryu/ferryfile/data/server/ServerRepositoryImpl.kt`:

```kotlin
package ru.kryu.ferryfile.data.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kryu.ferryfile.domain.model.ServerAddress
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.AccessCodeRepository
import ru.kryu.ferryfile.domain.repository.NetworkRepository
import ru.kryu.ferryfile.domain.repository.ServerRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.server.KtorServer
import ru.kryu.ferryfile.service.FileServerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val network: NetworkRepository,
    private val accessCodes: AccessCodeRepository,
    private val server: KtorServer
) : ServerRepository {

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    override val state: StateFlow<ServerState> = _state.asStateFlow()

    override suspend fun start() {
        if (server.isRunning) return refresh()
        _state.value = ServerState.Starting
        val pin = accessCodes.issue()
        ContextCompat.startForegroundService(context, intent(FileServerService.ACTION_START))
        _state.value = ServerState.Running(address(), pin)
    }

    override suspend fun stop() {
        context.startService(intent(FileServerService.ACTION_STOP))
        accessCodes.revoke()
        _state.value = ServerState.Stopped
    }

    override suspend fun refresh() {
        _state.value = if (!server.isRunning) {
            accessCodes.revoke()
            ServerState.Stopped
        } else {
            ServerState.Running(address(), accessCodes.current ?: accessCodes.issue())
        }
    }

    private suspend fun address(): ServerAddress? =
        network.localAddress()?.let { ServerAddress(it, settings.port.value) }

    private fun intent(action: String) =
        Intent(context, FileServerService::class.java).apply { this.action = action }
}
```

- [ ] **Step 9: Реализовать оставшиеся use cases**

Create `app/src/main/java/ru/kryu/ferryfile/domain/usecase/ServerUseCases.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.repository.ServerRepository
import javax.inject.Inject

class ObserveServerStateUseCase @Inject constructor(private val server: ServerRepository) {
    operator fun invoke(): StateFlow<ServerState> = server.state
}

class StartServerUseCase @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke() = server.start()
}

class StopServerUseCase @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke() = server.stop()
}

class RefreshServerStateUseCase @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke() = server.refresh()
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/usecase/SettingsUseCases.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject

class ObservePortUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<Port> = settings.port
}

class ObserveDarkThemeUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<Boolean> = settings.darkTheme
}

class ObserveSharedFoldersUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<List<SharedFolder>> = settings.sharedFolders
}

class SetPortUseCase @Inject constructor(private val settings: SettingsRepository) {

    enum class Result { Saved, OutOfRange }

    suspend operator fun invoke(text: String): Result {
        val port = Port.parse(text) ?: return Result.OutOfRange
        settings.setPort(port)
        return Result.Saved
    }
}

class SetDarkThemeUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) = settings.setDarkTheme(enabled)
}

class AddSharedFolderUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(uri: String): Boolean = settings.addSharedFolder(uri)
}

class RemoveSharedFolderUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(uri: String) = settings.removeSharedFolder(uri)
}
```

Create `app/src/main/java/ru/kryu/ferryfile/domain/usecase/VerifyAccessCodeUseCase.kt`:

```kotlin
package ru.kryu.ferryfile.domain.usecase

import ru.kryu.ferryfile.domain.repository.AccessCodeRepository
import javax.inject.Inject

class VerifyAccessCodeUseCase @Inject constructor(
    private val accessCodes: AccessCodeRepository
) {

    operator fun invoke(candidate: String): Boolean = accessCodes.verify(candidate)
}
```

- [ ] **Step 10: Дописать биндинги**

В `RepositoryModule.kt` добавить:

```kotlin
    @Binds
    @Singleton
    abstract fun bindNetworkRepository(impl: WifiNetworkRepository): NetworkRepository

    @Binds
    @Singleton
    abstract fun bindAccessCodeRepository(impl: InMemoryAccessCodeRepository): AccessCodeRepository

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository
```

- [ ] **Step 11: Переписать HomeViewModel на use cases**

Replace `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeViewModel.kt`:

```kotlin
package ru.kryu.ferryfile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.usecase.ObserveServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveSharedFoldersUseCase
import ru.kryu.ferryfile.domain.usecase.RefreshServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.StartServerUseCase
import ru.kryu.ferryfile.domain.usecase.StopServerUseCase
import javax.inject.Inject

data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val url: String = "",
    val pin: String = "",
    val hasWifi: Boolean = true,
    val hasSharedFolders: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeServerState: ObserveServerStateUseCase,
    observeSharedFolders: ObserveSharedFoldersUseCase,
    private val startServer: StartServerUseCase,
    private val stopServer: StopServerUseCase,
    private val refreshServerState: RefreshServerStateUseCase
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(observeServerState(), observeSharedFolders()) { server, folders ->
            when (server) {
                is ServerState.Stopped -> HomeUiState(hasSharedFolders = folders.isNotEmpty())
                is ServerState.Starting -> HomeUiState(
                    isStarting = true,
                    hasSharedFolders = folders.isNotEmpty()
                )
                is ServerState.Running -> HomeUiState(
                    isRunning = true,
                    url = server.address?.asUrl().orEmpty(),
                    pin = server.pin.digits,
                    hasWifi = server.address != null,
                    hasSharedFolders = folders.isNotEmpty()
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onStartClicked() = viewModelScope.launch { startServer() }

    fun onStopClicked() = viewModelScope.launch { stopServer() }

    fun refresh() = viewModelScope.launch { refreshServerState() }
}
```

- [ ] **Step 12: Убрать QR из HomeScreen**

В `HomeScreen.kt` удалить импорты `Image`, `asImageBitmap` и весь блок `if (uiState.isRunning) { … qrBitmap … }`, заменив его на текстовый вывод (полноценный редизайн — Задача 9):

```kotlin
        if (uiState.isRunning) {
            Text(
                text = if (uiState.hasWifi) uiState.url else "No Wi-Fi connection",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.pin.isNotEmpty()) {
                Text(text = "PIN ${uiState.pin}", style = MaterialTheme.typography.titleMedium)
            }
        }
```

Кнопку Start/Stop перевести на новые методы:

```kotlin
        Button(
            onClick = { if (uiState.isRunning) viewModel.onStopClicked() else viewModel.onStartClicked() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (uiState.isRunning) "Stop Server" else "Start Server")
        }
```

Блок `enabled = uiState.hasPassword` и карточку предупреждения про пароль удалить — сервер теперь стартует всегда. `LaunchedEffect(Unit) { viewModel.refreshStatus() }` заменить на `viewModel.refresh()`.

- [ ] **Step 13: Переписать SettingsViewModel на use cases**

Replace `app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsViewModel.kt`:

```kotlin
package ru.kryu.ferryfile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.usecase.AddSharedFolderUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveDarkThemeUseCase
import ru.kryu.ferryfile.domain.usecase.ObservePortUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveSharedFoldersUseCase
import ru.kryu.ferryfile.domain.usecase.RemoveSharedFolderUseCase
import ru.kryu.ferryfile.domain.usecase.SetDarkThemeUseCase
import ru.kryu.ferryfile.domain.usecase.SetPortUseCase
import javax.inject.Inject

data class SettingsUiState(
    val port: Int = 8080,
    val portError: Boolean = false,
    val darkTheme: Boolean = true,
    val sharedFolders: List<SharedFolder> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observePort: ObservePortUseCase,
    observeDarkTheme: ObserveDarkThemeUseCase,
    observeSharedFolders: ObserveSharedFoldersUseCase,
    private val setPort: SetPortUseCase,
    private val setDarkTheme: SetDarkThemeUseCase,
    private val addSharedFolder: AddSharedFolderUseCase,
    private val removeSharedFolder: RemoveSharedFolderUseCase
) : ViewModel() {

    private val portError = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        observePort(), observeDarkTheme(), observeSharedFolders(), portError
    ) { port, dark, folders, error ->
        SettingsUiState(
            port = port.value,
            portError = error,
            darkTheme = dark,
            sharedFolders = folders
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onPortChanged(text: String) {
        viewModelScope.launch {
            portError.value = setPort(text) == SetPortUseCase.Result.OutOfRange
        }
    }

    fun onDarkThemeChanged(enabled: Boolean) = viewModelScope.launch { setDarkTheme(enabled) }

    fun onFolderPicked(uri: String) = viewModelScope.launch { addSharedFolder(uri) }

    fun onFolderRemoved(uri: String) = viewModelScope.launch { removeSharedFolder(uri) }
}
```

`SettingsScreen.kt` привести в соответствие: секцию Password удалить целиком, `onValueChange` вызывает `viewModel.onPortChanged(newValue)` без собственной проверки диапазона, `isError = uiState.portError`, список папок рисуется по `uiState.sharedFolders` (`folder.displayName`, `viewModel.onFolderRemoved(folder.uri)`), picker отдаёт `viewModel.onFolderPicked(uri.toString())`.

- [ ] **Step 14: Обновить MainActivity и удалить SafFileProvider**

`MainActivity` переводится на `ObserveDarkThemeUseCase`:

```kotlin
    @Inject lateinit var observeDarkTheme: ObserveDarkThemeUseCase
```
```kotlin
            val darkTheme by observeDarkTheme().collectAsStateWithLifecycle()
```

`KtorServer` и `FileRoutes` временно переключаются с `SafFileProvider` на `ListDirectoryUseCase` (полная перепись роутов — Задачи 6–7; на этом шаге достаточно заменить `/api/list` на use case, а `/api/download` и `/api/upload` — на `DownloadSelectionUseCase`/`SaveUploadUseCase` минимальным способом, сохранив текущее поведение). Затем:

```bash
git rm app/src/main/java/ru/kryu/ferryfile/server/saf/SafFileProvider.kt
```

Сигнатура роутов сразу принимает финальный вид (он же используется в Задачах 6 и 7), чтобы не переписывать её дважды:

```kotlin
fun Application.configureFileRoutes(
    listDirectory: ListDirectoryUseCase,
    downloadSelection: DownloadSelectionUseCase,
    saveUpload: SaveUploadUseCase,
    transferProgress: TransferProgress,
    zipStreamWriter: ZipStreamWriter,
    assets: AssetManager
)
```

На этом шаге `ZipStreamWriter` ещё не существует — вместо него передаётся текущий `DownloadHandler`, а параметр переименовывается в Задаче 7.

`FileRoutesTest` перестраивается вокруг `FakeFileStorageRepository`; этот каркас дальше дополняют Задачи 6 и 7, поэтому имена полей (`storage`, `sessionManager`, `transferProgress`) фиксируются здесь:

```kotlin
package ru.kryu.ferryfile.server.routes

import android.content.res.AssetManager
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import ru.kryu.ferryfile.domain.FakeFileStorageRepository
import ru.kryu.ferryfile.domain.usecase.DownloadSelectionUseCase
import ru.kryu.ferryfile.domain.usecase.ListDirectoryUseCase
import ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase
import ru.kryu.ferryfile.server.auth.SessionManager
import ru.kryu.ferryfile.server.transfer.TransferProgress

class FileRoutesTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var transferProgress: TransferProgress
    private lateinit var storage: FakeFileStorageRepository
    private val assets: AssetManager = mock()

    @Before fun setUp() {
        sessionManager = SessionManager()
        transferProgress = TransferProgress()
        storage = FakeFileStorageRepository()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application {
            configureAuthForTest(sessionManager)
            configureFileRoutes(
                ListDirectoryUseCase(storage),
                DownloadSelectionUseCase(storage),
                SaveUploadUseCase(storage),
                transferProgress,
                DownloadHandler(),
                assets
            )
        }
        block()
    }

    @Test fun `GET api-list without session returns 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/list?path=/").status)
    }

    @Test fun `GET api-list returns the shared folders at the root`() = withApp {
        storage.addDirectory("0")
        val token = sessionManager.createSession()

        val res = client.get("/api/list?path=%2F") { cookie("FERRYFILE_SESSION", token) }

        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test fun `GET api-list rejects a traversal path`() = withApp {
        val token = sessionManager.createSession()
        val res = client.get("/api/list?path=0%2F..%2Fetc") { cookie("FERRYFILE_SESSION", token) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
```

В Задаче 7 аргумент `DownloadHandler()` заменяется на `ZipStreamWriter()`.

- [ ] **Step 15: Удалить зависимость zxing**

В `gradle/libs.versions.toml` удалить строку `zxing = "3.5.3"` из `[versions]` и `zxing-core = …` из `[libraries]`. В `app/build.gradle.kts` удалить `implementation(libs.zxing.core)`.

- [ ] **Step 16: Прогнать тесты и сборку**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 17: Коммит**

```bash
git add -A
git commit -m "refactor: move business logic into use cases and drop QR code generation"
```

---

### Task 6: Починить загрузку с ПК на телефон

Три бага сразу: несериализуемый ответ, запись в виртуальный корень и отсутствие события `Done`. Плюс снимается глобальный лок и появляется `transferId`.

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/transfer/TransferProgress.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/routes/SseRoutes.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt`
- Modify: `app/src/main/assets/webui/app.js`
- Delete: `app/src/main/java/ru/kryu/ferryfile/server/transfer/UploadHandler.kt`
- Delete: `app/src/test/java/ru/kryu/ferryfile/server/transfer/UploadHandlerTest.kt`
- Delete: `app/src/main/java/ru/kryu/ferryfile/server/transfer/TransferEvent.kt` (модель переехала в domain в Задаче 2)
- Test: `app/src/test/java/ru/kryu/ferryfile/server/transfer/TransferProgressTest.kt` (переписывается)
- Test: `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt` (дописывается)

Копирование потока переехало в `SaveUploadUseCase` (Задача 5), поэтому однометодный `UploadHandler` удаляется, а его поведение покрыто `SaveUploadUseCaseTest`.

**Interfaces:**
- Consumes: `SaveUploadUseCase`, `ListDirectoryUseCase`, `FilePath`, `TransferEvent` (Задачи 2–5)
- Produces:
  - `TransferProgress.emitProgress(transferId: String, file: String, bytes: Long, total: Long, startedAtMillis: Long)`, `emitDone(transferId: String, files: Int, bytes: Long)`, `emitError(transferId: String, code: String, message: String)`, `events: SharedFlow<TransferEvent>`
  - `UploadResponse(files: Int, bytes: Long)` и `ErrorResponse(error: String)` — сериализуемые тела ответов `FileRoutes`

- [ ] **Step 1: Переписать TransferProgressTest под новый контракт**

Replace `app/src/test/java/ru/kryu/ferryfile/server/transfer/TransferProgressTest.kt`:

```kotlin
package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.model.TransferEvent

class TransferProgressTest {

    private val progress = TransferProgress()

    @Test fun `progress carries percentage computed from the total size`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitProgress("tx-1", "movie.mp4", bytes = 512, total = 2048, startedAtMillis = 0L)

        val event = received.await() as TransferEvent.Progress
        assertEquals("tx-1", event.transferId)
        assertEquals("movie.mp4", event.file)
        assertEquals(25, event.pct)
    }

    @Test fun `percentage is zero when the total size is unknown`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitProgress("tx-1", "movie.mp4", bytes = 512, total = -1, startedAtMillis = 0L)

        assertEquals(0, (received.await() as TransferEvent.Progress).pct)
    }

    @Test fun `percentage never exceeds one hundred`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitProgress("tx-1", "movie.mp4", bytes = 4096, total = 2048, startedAtMillis = 0L)

        assertEquals(100, (received.await() as TransferEvent.Progress).pct)
    }

    @Test fun `done reports the number of files and bytes`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitDone("tx-2", files = 3, bytes = 900)

        val event = received.await() as TransferEvent.Done
        assertEquals("tx-2", event.transferId)
        assertEquals(3, event.files)
        assertEquals(900L, event.bytes)
    }

    @Test fun `error carries the code and message`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { progress.events.first() }

        progress.emitError("tx-3", "upload_failed", "disk full")

        val event = received.await() as TransferEvent.Error
        assertEquals("upload_failed", event.code)
        assertEquals("disk full", event.message)
    }
}
```

- [ ] **Step 2: Прогнать — должен падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.transfer.TransferProgressTest" --console=plain`
Expected: FAIL, «Unresolved reference: emitProgress»

- [ ] **Step 3: Переписать TransferProgress**

Replace `app/src/main/java/ru/kryu/ferryfile/server/transfer/TransferProgress.kt`:

```kotlin
package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.kryu.ferryfile.domain.model.TransferEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шина событий передачи. Глобальной блокировки нет: параллельные передачи различаются
 * по [TransferEvent.transferId], который генерирует клиент.
 */
@Singleton
class TransferProgress @Inject constructor() {

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    fun emitProgress(
        transferId: String,
        file: String,
        bytes: Long,
        total: Long,
        startedAtMillis: Long
    ) {
        val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
        _events.tryEmit(
            TransferEvent.Progress(
                transferId = transferId,
                file = file,
                bytes = bytes,
                total = total,
                pct = pct,
                etaSeconds = etaSeconds(bytes, total, startedAtMillis)
            )
        )
    }

    fun emitDone(transferId: String, files: Int, bytes: Long) {
        _events.tryEmit(TransferEvent.Done(transferId, files, bytes))
    }

    fun emitError(transferId: String, code: String, message: String) {
        _events.tryEmit(TransferEvent.Error(transferId, code, message))
    }

    private fun etaSeconds(bytes: Long, total: Long, startedAtMillis: Long): Int {
        if (total <= 0 || bytes <= 0 || startedAtMillis <= 0L) return UNKNOWN_ETA
        val elapsed = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(1L)
        val remaining = (total - bytes).coerceAtLeast(0L)
        return ((remaining * elapsed) / bytes / 1000L).toInt()
    }

    private companion object {
        const val UNKNOWN_ETA = -1
    }
}
```

- [ ] **Step 4: Прогнать TransferProgressTest — должен пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.transfer.TransferProgressTest" --console=plain`
Expected: PASS, 5 тестов

- [ ] **Step 5: Перевести SSE-роут на DTO**

Replace `app/src/main/java/ru/kryu/ferryfile/server/routes/SseRoutes.kt`:

```kotlin
package ru.kryu.ferryfile.server.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.kryu.ferryfile.domain.model.TransferEvent
import ru.kryu.ferryfile.server.transfer.TransferProgress

@Serializable
private data class ProgressDto(
    val transferId: String,
    val file: String,
    val bytes: Long,
    val total: Long,
    val pct: Int,
    val eta: Int
)

@Serializable
private data class DoneDto(val transferId: String, val files: Int, val bytes: Long)

@Serializable
private data class ErrorDto(val transferId: String, val code: String, val message: String)

fun Application.configureSseRoutes(transferProgress: TransferProgress) {
    routing {
        authenticate("session") {
            sse("/api/progress") {
                transferProgress.events.collect { event ->
                    val (name, data) = when (event) {
                        is TransferEvent.Progress -> "progress" to Json.encodeToString(
                            ProgressDto(
                                event.transferId, event.file, event.bytes,
                                event.total, event.pct, event.etaSeconds
                            )
                        )

                        is TransferEvent.Done -> "done" to Json.encodeToString(
                            DoneDto(event.transferId, event.files, event.bytes)
                        )

                        is TransferEvent.Error -> "error" to Json.encodeToString(
                            ErrorDto(event.transferId, event.code, event.message)
                        )
                    }
                    send(ServerSentEvent(data = data, event = name))
                }
            }
        }
    }
}
```

- [ ] **Step 6: Написать падающие тесты роута загрузки**

Добавить в `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt` (файл к этому моменту уже собран вокруг `FakeFileStorageRepository`):

```kotlin
    private fun multipart(fileName: String, content: String) = MultiPartFormDataContent(
        formData {
            append(
                "file", content.toByteArray(),
                Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                }
            )
        }
    )

    @Test fun `upload stores the file and answers with a serializable body`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        val token = sessionManager.createSession()

        val res = client.post("/api/upload?path=0%2Fdocs&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello ferry"))
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"files":1,"bytes":11}""", res.bodyAsText())
        assertEquals("hello ferry", storage.writtenFiles["0/docs/notes.txt"]!!.toString(Charsets.UTF_8.name()))
    }

    @Test fun `upload emits a done event`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        val token = sessionManager.createSession()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val done = scope.async(start = CoroutineStart.UNDISPATCHED) {
            transferProgress.events.filterIsInstance<TransferEvent.Done>().first()
        }

        client.post("/api/upload?path=0%2Fdocs&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello ferry"))
        }

        val event = withTimeout(5_000) { done.await() }
        assertEquals("tx-1", event.transferId)
        assertEquals(1, event.files)
        assertEquals(11L, event.bytes)
        scope.cancel()
    }

    @Test fun `upload into the virtual root is rejected with an explanation`() = withApp {
        val token = sessionManager.createSession()

        val res = client.post("/api/upload?path=%2F&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello"))
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"root_not_writable"}""", res.bodyAsText())
    }

    @Test fun `upload with a malformed path is rejected`() = withApp {
        val token = sessionManager.createSession()

        val res = client.post("/api/upload?path=0%2F..%2Fetc&transferId=tx-1") {
            cookie("FERRYFILE_SESSION", token)
            setBody(multipart("notes.txt", "hello"))
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"invalid_path"}""", res.bodyAsText())
    }

    @Test fun `upload without a session is rejected`() = withApp {
        val res = client.post("/api/upload?path=0%2Fdocs&transferId=tx-1") {
            setBody(multipart("notes.txt", "hello"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
```

Импорты, которые понадобятся в тесте: `io.ktor.client.request.forms.*`, `io.ktor.client.statement.bodyAsText`, `kotlinx.coroutines.*`, `kotlinx.coroutines.flow.filterIsInstance`, `kotlinx.coroutines.flow.first`, `ru.kryu.ferryfile.domain.model.TransferEvent`.

- [ ] **Step 7: Прогнать — должны падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.FileRoutesTest" --console=plain`
Expected: FAIL — ответ приходит с 500 либо тело не совпадает

- [ ] **Step 8: Переписать роут загрузки**

В `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt` заменить блок `post("/api/upload")` и добавить тела ответов:

```kotlin
@Serializable
data class UploadResponse(val files: Int, val bytes: Long)

@Serializable
data class ErrorResponse(val error: String)
```

```kotlin
            post("/api/upload") {
                val dir = FilePath.parse(call.request.queryParameters["path"])
                if (dir == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path"))
                    return@post
                }
                if (dir.isRoot) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("root_not_writable"))
                    return@post
                }

                val transferId = call.request.queryParameters["transferId"].orEmpty()
                val totalBytes = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: -1L
                val startedAt = System.currentTimeMillis()
                var written = 0L
                var files = 0
                var lastEmitAt = 0L

                try {
                    val multipart = call.receiveMultipart()
                    var part = multipart.readPart()
                    while (part != null) {
                        try {
                            if (part is PartData.FileItem) {
                                val fileName = part.originalFileName.orEmpty()
                                val mimeType = part.contentType?.toString() ?: BINARY_MIME
                                val alreadyWritten = written
                                val source = part.provider().toInputStream()

                                val result = withContext(Dispatchers.IO) {
                                    saveUpload(dir, fileName, mimeType, source) { chunkBytes ->
                                        val now = System.currentTimeMillis()
                                        if (now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                                            lastEmitAt = now
                                            transferProgress.emitProgress(
                                                transferId, fileName,
                                                alreadyWritten + chunkBytes, totalBytes, startedAt
                                            )
                                        }
                                    }
                                }

                                when (result) {
                                    is SaveUploadUseCase.Result.Saved -> {
                                        written = alreadyWritten + result.bytesWritten
                                        files++
                                    }

                                    SaveUploadUseCase.Result.RootNotWritable,
                                    SaveUploadUseCase.Result.Failed ->
                                        throw IOException("Cannot store $fileName")
                                }
                            }
                        } finally {
                            part.dispose()
                        }
                        part = multipart.readPart()
                    }

                    transferProgress.emitDone(transferId, files, written)
                    call.respond(UploadResponse(files, written))
                } catch (e: Exception) {
                    transferProgress.emitError(transferId, "upload_failed", e.message ?: "Upload failed")
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("upload_failed"))
                }
            }
```

Константы файла:

```kotlin
private const val BINARY_MIME = "application/octet-stream"
private const val PROGRESS_INTERVAL_MS = 200L
```

Нужные импорты: `io.ktor.utils.io.jvm.javaio.toInputStream`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`, `ru.kryu.ferryfile.domain.model.FilePath`, `ru.kryu.ferryfile.domain.usecase.SaveUploadUseCase`, `java.io.IOException`.

Сигнатура `configureFileRoutes` принимает use cases:

```kotlin
fun Application.configureFileRoutes(
    listDirectory: ListDirectoryUseCase,
    downloadSelection: DownloadSelectionUseCase,
    saveUpload: SaveUploadUseCase,
    transferProgress: TransferProgress,
    zipStreamWriter: ZipStreamWriter,
    assets: AssetManager
)
```

`/api/list` переводится на `listDirectory(path)`, `isValidPath` больше не существует — некорректный путь отсекает `FilePath.parse`:

```kotlin
            get("/api/list") {
                val path = FilePath.parse(call.request.queryParameters["path"] ?: "/")
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path"))
                        return@get
                    }
                val items = listDirectory(path)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                        return@get
                    }
                call.respond(
                    FileListResponse(
                        path = path.raw,
                        items = items.map {
                            FileItemDto(it.name, it.sizeBytes, it.lastModified, it.isDirectory, it.path.raw)
                        }
                    )
                )
            }
```

- [ ] **Step 9: Удалить UploadHandler и старый TransferEvent**

```bash
git rm app/src/main/java/ru/kryu/ferryfile/server/transfer/UploadHandler.kt \
       app/src/test/java/ru/kryu/ferryfile/server/transfer/UploadHandlerTest.kt \
       app/src/main/java/ru/kryu/ferryfile/server/transfer/TransferEvent.kt
```

Обновить `KtorServer.kt`: вместо `safFileProvider`, `downloadHandler`, `uploadHandler` инжектируются `ListDirectoryUseCase`, `DownloadSelectionUseCase`, `SaveUploadUseCase`, `ZipStreamWriter`.

- [ ] **Step 10: Прогнать тесты роутов**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.FileRoutesTest" --console=plain`
Expected: PASS

- [ ] **Step 11: Обновить клиентскую часть загрузки**

В `app/src/main/assets/webui/app.js`:

добавить состояние и генератор идентификатора:

```javascript
  var currentTransferId = null;
  var isInitialLoad = true;

  function newTransferId() {
    return 'tx-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
  }
```

заменить `connectSSE` на версию, которая резолвится после открытия соединения и фильтрует чужие события:

```javascript
  function connectSSE(transferId) {
    closeSse();
    currentTransferId = transferId;
    showProgress();

    return new Promise(function (resolve) {
      var settled = false;
      function ready() {
        if (settled) return;
        settled = true;
        resolve();
      }

      sseSource = new EventSource('/api/progress');
      sseSource.onopen = ready;
      // Страховка: если браузер не сообщит об открытии, отправляем запрос всё равно.
      setTimeout(ready, 1500);

      sseSource.addEventListener('progress', function (e) {
        var data = parseEvent(e);
        if (!data || data.transferId !== currentTransferId) return;

        var pct = Math.min(100, Math.max(0, data.pct || 0));
        progressFill.style.width = pct + '%';
        progressPct.textContent = pct + '%';
        if (data.file) progressFilename.textContent = data.file;

        var details = '';
        if (data.bytes != null && data.total != null && data.total > 0) {
          details = formatBytes(data.bytes) + ' / ' + formatBytes(data.total);
        }
        if (typeof data.eta === 'number' && data.eta >= 0) {
          details += (details ? '  \xB7  ' : '') + 'ETA ' + data.eta + 's';
        }
        progressDetails.textContent = details;
      });

      sseSource.addEventListener('done', function (e) {
        var data = parseEvent(e);
        if (!data || data.transferId !== currentTransferId) return;

        progressFill.style.width = '100%';
        progressPct.textContent = '100%';

        var msg = data.files + ' file' + (data.files !== 1 ? 's' : '') + ' transferred';
        if (data.bytes != null) msg += ' (' + formatBytes(data.bytes) + ')';
        showToast(msg, 'success');

        setTimeout(function () {
          hideProgress();
          loadPath(currentPath);
        }, 800);
        closeSse();
      });

      sseSource.addEventListener('error', function (e) {
        var data = parseEvent(e);
        if (data && data.transferId !== currentTransferId) return;
        showToast((data && data.message) || 'Transfer error', 'error');
        hideProgress();
        closeSse();
      });

      sseSource.onerror = function () {
        if (sseSource && sseSource.readyState === EventSource.CLOSED) {
          hideProgress();
          sseSource = null;
        }
      };
    });
  }

  function parseEvent(e) {
    try {
      return JSON.parse(e.data);
    } catch (ex) {
      return null;
    }
  }
```

заменить `handleUpload`:

```javascript
  function handleUpload(files, path) {
    if (!files || files.length === 0) return;
    if (path === '/') {
      showToast('Open a folder first — the home screen only lists shared folders', 'error');
      return;
    }

    var transferId = newTransferId();
    var formData = new FormData();
    for (var i = 0; i < files.length; i++) {
      formData.append('file', files[i]);
    }

    connectSSE(transferId)
      .then(function () {
        return fetch(
          '/api/upload?path=' + encodeURIComponent(path) +
          '&transferId=' + encodeURIComponent(transferId),
          { method: 'POST', body: formData }
        );
      })
      .then(function (res) {
        if (res.status === 401) { window.location.href = '/login'; return; }
        if (!res.ok) throw new Error('Upload failed: ' + res.status);
      })
      .catch(function (err) {
        showToast('Upload error: ' + err.message, 'error');
        hideProgress();
        closeSse();
      });
  }
```

в `loadPath` скрывать загрузку в корне и открывать единственную расшаренную папку автоматически:

```javascript
  function applyUploadVisibility(path) {
    var atRoot = path === '/';
    document.getElementById('upload-label').hidden = atRoot;
    dropZoneEl.hidden = atRoot;
    rootHintEl.hidden = !atRoot;
  }
```

внутри `.then(function (data) { … })` после `renderItems(data.items)`:

```javascript
        if (isInitialLoad) {
          isInitialLoad = false;
          if (path === '/' && data.items.length === 1 && data.items[0].isDirectory) {
            loadPath(data.items[0].path);
            return;
          }
        }
```

и вызов `applyUploadVisibility(path)` в начале `loadPath`. В `files.html` добавить подсказку рядом с тулбаром:

```html
      <p class="root-hint" id="root-hint" hidden>
        Open one of the folders below to upload files into it.
      </p>
```

и объявить ссылку `var rootHintEl = document.getElementById('root-hint');` рядом с остальными DOM-ссылками.

- [ ] **Step 12: Прогнать все тесты и собрать**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 13: Коммит**

```bash
git add -A
git commit -m "fix(upload): store files from the browser and report real progress

- respond with a serializable UploadResponse instead of a heterogeneous map
- reject uploads into the virtual root with an explicit error
- emit Done/Error events so the progress bar completes
- drop the global transfer lock in favour of per-transfer ids"
```

---

### Task 7: Скачивание — файл напрямую, папка и выборка архивом

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt`
- Rename: `app/src/main/java/ru/kryu/ferryfile/server/transfer/DownloadHandler.kt` → `ZipStreamWriter.kt`
- Rename: `app/src/test/java/ru/kryu/ferryfile/server/transfer/DownloadHandlerTest.kt` → `ZipStreamWriterTest.kt`
- Modify: `app/src/main/assets/webui/app.js`
- Test: `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt`

**Interfaces:**
- Consumes: `DownloadSelectionUseCase` (Задача 5)
- Produces:
  - `ZipStreamWriter.Entry(name: String, openStream: suspend () -> InputStream?)`, `suspend ZipStreamWriter.write(entries: List<Entry>, output: OutputStream)`
  - `internal fun attachmentHeader(fileName: String): String` в пакете `ru.kryu.ferryfile.server.routes`

- [ ] **Step 1: Написать падающий ZipStreamWriterTest**

Create `app/src/test/java/ru/kryu/ferryfile/server/transfer/ZipStreamWriterTest.kt` (и удалить `DownloadHandlerTest.kt`):

```kotlin
package ru.kryu.ferryfile.server.transfer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ZipStreamWriterTest {

    private val writer = ZipStreamWriter()

    @Test fun `writes every entry with its path preserved`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(
            listOf(
                ZipStreamWriter.Entry("docs/a.txt") { "alpha".byteInputStream() },
                ZipStreamWriter.Entry("docs/sub/b.txt") { "beta".byteInputStream() }
            ),
            out
        )

        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertEquals(mapOf("docs/a.txt" to "alpha", "docs/sub/b.txt" to "beta"), entries)
    }

    @Test fun `unreadable entries are skipped instead of breaking the archive`() = runTest {
        val out = ByteArrayOutputStream()

        writer.write(
            listOf(
                ZipStreamWriter.Entry("ghost.txt") { null },
                ZipStreamWriter.Entry("a.txt") { "alpha".byteInputStream() }
            ),
            out
        )

        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }

        assertEquals(listOf("a.txt"), names)
    }

    @Test fun `empty selection produces a valid empty archive`() = runTest {
        val out = ByteArrayOutputStream()
        writer.write(emptyList(), out)
        assertTrue(out.size() > 0)
    }
}
```

- [ ] **Step 2: Прогнать — должен падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.transfer.ZipStreamWriterTest" --console=plain`
Expected: FAIL, «Unresolved reference: ZipStreamWriter»

- [ ] **Step 3: Реализовать ZipStreamWriter**

Create `app/src/main/java/ru/kryu/ferryfile/server/transfer/ZipStreamWriter.kt` и удалить `DownloadHandler.kt`:

```kotlin
package ru.kryu.ferryfile.server.transfer

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Пишет выбранные файлы в ZIP на лету, не собирая архив в памяти. */
@Singleton
class ZipStreamWriter @Inject constructor() {

    data class Entry(val name: String, val openStream: suspend () -> InputStream?)

    suspend fun write(entries: List<Entry>, output: OutputStream) {
        val zip = ZipOutputStream(output)
        for (entry in entries) {
            val source = entry.openStream() ?: continue
            zip.putNextEntry(ZipEntry(entry.name))
            source.use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }
}
```

- [ ] **Step 4: Прогнать — должен пройти**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.transfer.ZipStreamWriterTest" --console=plain`
Expected: PASS, 3 теста

- [ ] **Step 5: Написать падающие тесты роута скачивания**

Добавить в `FileRoutesTest.kt`:

```kotlin
    @Test fun `single file is streamed as is, not zipped`() = withApp {
        storage.addDirectory("0")
        storage.addFile("0/report.pdf", "pdf-bytes", "application/pdf")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Freport.pdf") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("pdf-bytes", res.bodyAsText())
        assertEquals("application/pdf", res.contentType()?.withoutParameters()?.toString())
        val disposition = res.headers[HttpHeaders.ContentDisposition]!!
        assertTrue(disposition.contains("""filename="report.pdf""""))
        assertFalse(disposition.contains(".zip"))
    }

    @Test fun `folder is streamed as a zip named after the folder`() = withApp {
        storage.addDirectory("0")
        storage.addDirectory("0/docs")
        storage.addFile("0/docs/a.txt", "alpha")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Fdocs") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.ContentDisposition]!!.contains("""filename="docs.zip""""))
        assertEquals(listOf("docs/a.txt"), zipEntryNames(res.readRawBytes()))
    }

    @Test fun `multiple paths are packed into one selection archive`() = withApp {
        storage.addDirectory("0")
        storage.addFile("0/a.txt", "alpha")
        storage.addFile("0/b.txt", "beta")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Fa.txt&path=0%2Fb.txt") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertTrue(res.headers[HttpHeaders.ContentDisposition]!!.contains("ferryfile-selection.zip"))
        assertEquals(listOf("a.txt", "b.txt"), zipEntryNames(res.readRawBytes()).sorted())
    }

    @Test fun `unknown path returns 404`() = withApp {
        storage.addDirectory("0")
        val token = sessionManager.createSession()

        val res = client.get("/api/download?path=0%2Fghost") {
            cookie("FERRYFILE_SESSION", token)
        }

        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test fun `download without a path is rejected`() = withApp {
        val token = sessionManager.createSession()
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/download") { cookie("FERRYFILE_SESSION", token) }.status
        )
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }
```

И отдельный тест заголовка в том же пакете, `app/src/test/java/ru/kryu/ferryfile/server/routes/AttachmentHeaderTest.kt`:

```kotlin
package ru.kryu.ferryfile.server.routes

import org.junit.Assert.*
import org.junit.Test

class AttachmentHeaderTest {

    @Test fun `ascii name is used verbatim in both parameters`() {
        assertEquals(
            "attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf",
            attachmentHeader("report.pdf")
        )
    }

    @Test fun `non ascii name keeps an ascii fallback and a percent encoded form`() {
        val header = attachmentHeader("Отчёт.pdf")
        assertTrue(header.startsWith("attachment; filename=\"_____.pdf\""))
        assertTrue(header.contains("filename*=UTF-8''%D0%9E"))
    }

    @Test fun `quotes and backslashes cannot break out of the header`() {
        val header = attachmentHeader("a\"b\\c.txt")
        assertTrue(header.startsWith("attachment; filename=\"a_b_c.txt\""))
    }

    @Test fun `spaces are encoded as percent twenty, not plus`() {
        assertTrue(attachmentHeader("my report.pdf").contains("filename*=UTF-8''my%20report.pdf"))
    }
}
```

- [ ] **Step 6: Прогнать — должны падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.*" --console=plain`
Expected: FAIL, «Unresolved reference: attachmentHeader», архив вместо файла

- [ ] **Step 7: Переписать роут скачивания**

В `FileRoutes.kt` заменить блок `get("/api/download")`:

```kotlin
            get("/api/download") {
                val rawPaths = call.request.queryParameters.getAll("path").orEmpty()
                val paths = rawPaths.mapNotNull { FilePath.parse(it) }
                if (rawPaths.isEmpty() || paths.size != rawPaths.size) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_path"))
                    return@get
                }

                when (val selection = downloadSelection.resolve(paths)) {
                    DownloadSelectionUseCase.Selection.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))

                    is DownloadSelectionUseCase.Selection.SingleFile -> {
                        val node = selection.node
                        val stream = downloadSelection.open(node.path)
                        if (stream == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                            return@get
                        }
                        call.response.header(HttpHeaders.ContentDisposition, attachmentHeader(node.name))
                        if (node.sizeBytes > 0) {
                            call.response.header(HttpHeaders.ContentLength, node.sizeBytes.toString())
                        }
                        val contentType = runCatching { ContentType.parse(node.mimeType) }
                            .getOrDefault(ContentType.Application.OctetStream)
                        call.respondOutputStream(contentType) {
                            withContext(Dispatchers.IO) { stream.use { it.copyTo(this@respondOutputStream) } }
                        }
                    }

                    is DownloadSelectionUseCase.Selection.Archive -> {
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            attachmentHeader(selection.fileName)
                        )
                        call.respondOutputStream(ContentType.Application.Zip) {
                            val entries = selection.entries.map { source ->
                                ZipStreamWriter.Entry(source.entryName) { downloadSelection.open(source.path) }
                            }
                            withContext(Dispatchers.IO) {
                                zipStreamWriter.write(entries, this@respondOutputStream)
                            }
                        }
                    }
                }
            }
```

И добавить в конец файла:

```kotlin
/**
 * `Content-Disposition` с ASCII-запасным именем и RFC 5987-формой для кириллицы и прочего
 * не-ASCII. Без `filename*` браузер сохранит файл под искажённым именем.
 */
internal fun attachmentHeader(fileName: String): String {
    val asciiFallback = fileName.map { char ->
        if (char.code in 32..126 && char != '"' && char != '\\') char else '_'
    }.joinToString("")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
    return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}
```

Импорт: `java.net.URLEncoder`.

- [ ] **Step 8: Прогнать тесты роутов**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.*" --console=plain`
Expected: PASS

- [ ] **Step 9: Обновить клиент под новый контракт скачивания**

В `app.js` заменить `downloadFile`:

```javascript
  function downloadPaths(paths) {
    if (!paths || paths.length === 0) return;
    var query = paths.map(function (p) {
      return 'path=' + encodeURIComponent(p);
    }).join('&');
    window.location.href = '/api/download?' + query;
  }
```

и вызывать `downloadPaths([item.path])` там, где раньше вызывался `downloadFile(item.path)`.

- [ ] **Step 10: Прогнать всё и собрать**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Коммит**

```bash
git add -A
git commit -m "fix(download): stream single files as-is and zip only folders or multi-selections"
```

---

### Task 8: Одноразовый PIN вместо пароля

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/routes/AuthRoutes.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/server/KtorServer.kt`
- Modify: `app/src/main/assets/webui/login.html`
- Delete: `app/src/main/java/ru/kryu/ferryfile/server/auth/PasswordHasher.kt`
- Delete: `app/src/test/java/ru/kryu/ferryfile/server/auth/PasswordHasherTest.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/server/routes/AuthRoutesTest.kt` (переписывается)

**Interfaces:**
- Consumes: `VerifyAccessCodeUseCase`, `InMemoryAccessCodeRepository` (Задача 5)
- Produces: `configureAuthRoutes(sessionManager: SessionManager, verifyAccessCode: VerifyAccessCodeUseCase)`

- [ ] **Step 1: Переписать AuthRoutesTest под PIN**

Replace `app/src/test/java/ru/kryu/ferryfile/server/routes/AuthRoutesTest.kt`:

```kotlin
package ru.kryu.ferryfile.server.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.kryu.ferryfile.data.server.InMemoryAccessCodeRepository
import ru.kryu.ferryfile.domain.usecase.VerifyAccessCodeUseCase
import ru.kryu.ferryfile.server.auth.SessionManager

class AuthRoutesTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var accessCodes: InMemoryAccessCodeRepository
    private lateinit var pin: String

    @Before fun setUp() {
        sessionManager = SessionManager()
        accessCodes = InMemoryAccessCodeRepository()
        pin = accessCodes.issue().digits
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) { json() }
        application { configureAuthRoutes(sessionManager, VerifyAccessCodeUseCase(accessCodes)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(candidate: String) =
        client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"pin":"$candidate"}""")
        }

    @Test fun `correct pin returns 200 and sets a session cookie`() = withApp {
        val res = login(pin)
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.SetCookie]!!.contains("FERRYFILE_SESSION"))
    }

    @Test fun `wrong pin returns 401`() = withApp {
        val wrong = if (pin == "000000") "111111" else "000000"
        assertEquals(HttpStatusCode.Unauthorized, login(wrong).status)
    }

    @Test fun `fourth failed attempt is rate limited`() = withApp {
        val wrong = if (pin == "000000") "111111" else "000000"
        repeat(3) { login(wrong) }
        assertEquals(HttpStatusCode.TooManyRequests, login(wrong).status)
    }

    @Test fun `revoked pin stops being accepted`() = withApp {
        accessCodes.revoke()
        assertEquals(HttpStatusCode.Unauthorized, login(pin).status)
    }

    @Test fun `malformed body returns 400`() = withApp {
        val res = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"secret"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test fun `logout clears the session cookie`() = withApp {
        val res = client.post("/logout")
        assertEquals(HttpStatusCode.OK, res.status)
    }
}
```

- [ ] **Step 2: Прогнать — должен падать**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.AuthRoutesTest" --console=plain`
Expected: FAIL — сигнатура `configureAuthRoutes` не совпадает

- [ ] **Step 3: Перевести AuthRoutes на PIN**

В `app/src/main/java/ru/kryu/ferryfile/server/routes/AuthRoutes.kt` заменить сигнатуру, тело запроса и проверку:

```kotlin
@Serializable
private data class LoginRequest(val pin: String)

fun Application.configureAuthRoutes(
    sessionManager: SessionManager,
    verifyAccessCode: VerifyAccessCodeUseCase
) {
    install(Sessions) {
        cookie<UserSession>("FERRYFILE_SESSION") {
            cookie.httpOnly = true
            cookie.path = "/"
        }
    }

    install(Authentication) {
        session<UserSession>("session") {
            validate { if (sessionManager.isValidSession(it.token)) it else null }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }

    routing {
        post("/login") {
            val ip = call.request.local.remoteAddress
            if (sessionManager.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, "Too many attempts. Wait 30 seconds.")
                return@post
            }
            val request = runCatching { call.receive<LoginRequest>() }.getOrNull()
                ?: run { call.respond(HttpStatusCode.BadRequest); return@post }

            if (verifyAccessCode(request.pin)) {
                sessionManager.resetAttempts(ip)
                call.sessions.set(UserSession(sessionManager.createSession()))
                call.respond(HttpStatusCode.OK)
            } else {
                sessionManager.recordFailedAttempt(ip)
                call.respond(HttpStatusCode.Unauthorized, "Invalid PIN")
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK)
        }
    }
}
```

Импорт `ru.kryu.ferryfile.domain.usecase.VerifyAccessCodeUseCase`, импорт `PasswordHasher` удалить. `configureAuthForTest` в конце файла оставить без изменений — им пользуется `FileRoutesTest`.

- [ ] **Step 4: Обновить KtorServer и удалить PasswordHasher**

В `KtorServer.kt` убрать `passwordHasher` и чтение хеша, передать use case:

```kotlin
            configureAuthRoutes(sessionManager, verifyAccessCode)
```

```bash
git rm app/src/main/java/ru/kryu/ferryfile/server/auth/PasswordHasher.kt \
       app/src/test/java/ru/kryu/ferryfile/server/auth/PasswordHasherTest.kt
```

- [ ] **Step 5: Переписать экран входа на PIN**

В `app/src/main/assets/webui/login.html` заменить подзаголовок, поле и обработчик:

```html
      <p class="subtitle">Enter the PIN shown in the FerryFile app</p>

      <form id="login-form">
        <div class="form-group">
          <label for="pin">PIN</label>
          <input
            id="pin"
            type="text"
            inputmode="numeric"
            pattern="[0-9]*"
            maxlength="6"
            autocomplete="off"
            autofocus
            required
          />
        </div>

        <button type="submit" class="btn btn-primary" id="submit-btn">Unlock</button>
      </form>
```

```javascript
      var form = document.getElementById('login-form');
      var pinInput = document.getElementById('pin');
      var submitBtn = document.getElementById('submit-btn');
      var errorMsg = document.getElementById('error-msg');

      pinInput.addEventListener('input', function () {
        pinInput.value = pinInput.value.replace(/\D/g, '').slice(0, 6);
      });

      form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearError();

        var pin = pinInput.value;
        if (pin.length !== 6) {
          showError('The PIN is 6 digits');
          return;
        }

        submitBtn.disabled = true;
        submitBtn.textContent = 'Checking…';

        fetch('/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ pin: pin })
        })
          .then(function (res) {
            if (res.ok) {
              window.location.href = '/files';
              return;
            }
            if (res.status === 429) {
              showError('Too many attempts, wait 30s');
            } else if (res.status === 401) {
              showError('Wrong PIN');
              pinInput.value = '';
              pinInput.focus();
            } else {
              showError('Unexpected error, please try again');
            }
            submitBtn.disabled = false;
            submitBtn.textContent = 'Unlock';
          })
          .catch(function () {
            showError('Network error, please try again');
            submitBtn.disabled = false;
            submitBtn.textContent = 'Unlock';
          });
      });
```

- [ ] **Step 6: Прогнать всё и собрать**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Коммит**

```bash
git add -A
git commit -m "feat(auth): replace the stored password with a single-use 6-digit PIN"
```

---

### Task 9: Экраны Android — TopAppBar, адрес с PIN, подсказка про папки, «Стоп» в уведомлении

**Files:**
- Create: `app/src/main/res/drawable/ic_arrow_back.xml`
- Create: `app/src/main/res/drawable/ic_settings.xml`
- Create: `app/src/main/res/drawable/ic_stop.xml`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/service/FileServerService.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/data/server/ServerRepositoryImpl.kt`

**Interfaces:**
- Consumes: `HomeUiState`, `SettingsUiState` (Задача 5)
- Produces: `FileServerService.EXTRA_ADDRESS` — строка адреса для текста уведомления

- [ ] **Step 1: Добавить векторные иконки**

Зависимости `material-icons-core` на classpath нет, поэтому `Icons.AutoMirrored.Filled.ArrowBack` недоступен — иконки объявляются ресурсами.

Create `app/src/main/res/drawable/ic_arrow_back.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:autoMirrored="true">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z" />
</vector>
```

Create `app/src/main/res/drawable/ic_settings.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87c-0.12,0.21 -0.08,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z" />
</vector>
```

Create `app/src/main/res/drawable/ic_stop.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,6h12v12H6z" />
</vector>
```

- [ ] **Step 2: Проверить, что ресурсы компилируются**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Переписать HomeScreen**

Replace `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt`:

```kotlin
package ru.kryu.ferryfile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kryu.ferryfile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        // Отступы системных панелей уже заданы в AppNavigation, Scaffold их не добавляет.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FerryFile",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!uiState.hasSharedFolders) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No folders shared yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Add a folder so the browser has something to show.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(onClick = onNavigateToSettings) { Text("Add a folder") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = {
                    if (uiState.isRunning) viewModel.onStopClicked() else viewModel.onStartClicked()
                },
                enabled = !uiState.isStarting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (uiState.isRunning) "Stop Server" else "Start Server")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = if (uiState.isRunning) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                ) {}
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = when {
                        uiState.isStarting -> "Starting…"
                        uiState.isRunning -> "Running"
                        else -> "Stopped"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.isRunning) {
                if (uiState.hasWifi) {
                    ConnectionCard(url = uiState.url, pin = uiState.pin)
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "No Wi-Fi connection — connect this phone to the same network as your computer.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ConnectionCard(url: String, pin: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "OPEN IN YOUR BROWSER",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "PIN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = pin,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )
        }
    }
}
```

- [ ] **Step 4: Переписать шапку SettingsScreen**

В `SettingsScreen.kt` обернуть содержимое в `Scaffold` с `TopAppBar` и штатной стрелкой, заменив `TextButton { Text("← Back") }`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // …launcher…

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // существующие секции Port, Dark Theme, Storage Folders
        }
    }
}
```

Старый заголовок `Text("Settings", style = headlineMedium)` внутри колонки удалить — он переехал в `TopAppBar`.

- [ ] **Step 5: Передавать адрес в уведомление и добавить действие «Стоп»**

В `ServerRepositoryImpl.start()` вычислить адрес до запуска сервиса и положить его в Intent:

```kotlin
    override suspend fun start() {
        if (server.isRunning) return refresh()
        _state.value = ServerState.Starting
        val pin = accessCodes.issue()
        val serverAddress = address()
        ContextCompat.startForegroundService(
            context,
            intent(FileServerService.ACTION_START)
                .putExtra(FileServerService.EXTRA_ADDRESS, serverAddress?.asUrl())
        )
        _state.value = ServerState.Running(serverAddress, pin)
    }
```

В `FileServerService`:

```kotlin
    companion object {
        const val ACTION_START = "ru.kryu.ferryfile.START_SERVER"
        const val ACTION_STOP = "ru.kryu.ferryfile.STOP_SERVER"
        const val EXTRA_ADDRESS = "ru.kryu.ferryfile.EXTRA_ADDRESS"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ferryfile_server"
    }
```

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!ktorServer.isRunning) {
                val port = settings.port.value.value
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(intent.getStringExtra(EXTRA_ADDRESS)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                ktorServer.start(port)
            }

            ACTION_STOP -> {
                ktorServer.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(address: String?): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, FileServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FerryFile")
            .setContentText(address ?: "No Wi-Fi connection")
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }
```

Импорты: `android.app.PendingIntent`, `ru.kryu.ferryfile.domain.repository.SettingsRepository`.

Примечание: PIN в уведомление не выводится — оно видно на экране блокировки.

- [ ] **Step 6: Прогнать тесты и собрать**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Коммит**

```bash
git add -A
git commit -m "feat(ui): add top app bars with standard icons, connection card and notification stop action"
```

---

### Task 10: Веб-интерфейс — множественный выбор и внятные пустые состояния

**Files:**
- Modify: `app/src/main/assets/webui/files.html`
- Modify: `app/src/main/assets/webui/app.js`
- Modify: `app/src/main/assets/webui/style.css`

**Interfaces:**
- Consumes: `GET /api/download?path=…&path=…` (Задача 7), скрытие загрузки в корне (Задача 6)
- Produces: пользовательский сценарий «выбрал несколько — скачал одним архивом»

- [ ] **Step 1: Добавить разметку панели выделения**

В `files.html` между тулбаром и списком файлов:

```html
    <div class="selection-bar" id="selection-bar" hidden>
      <span id="selection-count">0 selected</span>
      <div class="selection-actions">
        <button class="btn btn-primary" id="download-selected">Download</button>
        <button class="btn btn-secondary" id="clear-selection">Clear</button>
      </div>
    </div>
```

- [ ] **Step 2: Добавить стили**

В конец `style.css`:

```css
/* Панель множественного выбора */
.selection-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 12px;
  border-radius: 8px;
  background: var(--surface-2, rgba(127, 127, 127, 0.12));
}

.selection-actions {
  display: flex;
  gap: 8px;
}

.file-checkbox {
  margin-right: 10px;
  width: 16px;
  height: 16px;
  flex: none;
  cursor: pointer;
}

.file-download-btn {
  margin-left: 8px;
  padding: 2px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.7;
}

.file-download-btn:hover {
  opacity: 1;
}

.root-hint {
  margin: 0 0 12px;
  opacity: 0.75;
  font-size: 0.9em;
}
```

Переменную `--surface-2` можно не объявлять — указан запасной цвет.

- [ ] **Step 3: Реализовать выделение в app.js**

Добавить состояние и элементы:

```javascript
  var selectedPaths = [];
  var selectionBarEl   = document.getElementById('selection-bar');
  var selectionCountEl = document.getElementById('selection-count');
  var downloadSelectedBtn = document.getElementById('download-selected');
  var clearSelectionBtn   = document.getElementById('clear-selection');
```

Функции выделения:

```javascript
  function isSelected(path) {
    return selectedPaths.indexOf(path) !== -1;
  }

  function toggleSelection(path, selected) {
    var index = selectedPaths.indexOf(path);
    if (selected && index === -1) selectedPaths.push(path);
    if (!selected && index !== -1) selectedPaths.splice(index, 1);
    renderSelectionBar();
  }

  function clearSelection() {
    selectedPaths = [];
    renderSelectionBar();
    var boxes = fileListEl.querySelectorAll('.file-checkbox');
    for (var i = 0; i < boxes.length; i++) boxes[i].checked = false;
  }

  function renderSelectionBar() {
    var count = selectedPaths.length;
    selectionBarEl.hidden = count === 0;
    selectionCountEl.textContent = count + ' selected';
  }

  downloadSelectedBtn.addEventListener('click', function () {
    downloadPaths(selectedPaths.slice());
  });

  clearSelectionBtn.addEventListener('click', clearSelection);
```

В `renderItems` для каждой строки добавить чекбокс первым элементом и кнопку скачивания для папок:

```javascript
      var checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.className = 'file-checkbox';
      checkbox.checked = isSelected(item.path);
      checkbox.setAttribute('aria-label', 'Select ' + item.name);
      checkbox.addEventListener('change', function () {
        toggleSelection(item.path, checkbox.checked);
      });

      row.appendChild(checkbox);
      row.appendChild(icon);
      row.appendChild(nameBtn);
      row.appendChild(meta);

      if (item.isDirectory) {
        var dirDownloadBtn = document.createElement('button');
        dirDownloadBtn.className = 'file-download-btn';
        dirDownloadBtn.textContent = 'Download';
        dirDownloadBtn.setAttribute('aria-label', 'Download folder ' + item.name);
        dirDownloadBtn.addEventListener('click', function () {
          downloadPaths([item.path]);
        });
        row.appendChild(dirDownloadBtn);
      }
```

Замыкание по `item` внутри `forEach` корректно — переменная объявлена параметром коллбэка.

В `loadPath` сбрасывать выделение при смене папки: вызвать `clearSelection()` сразу после `currentPath = path;`.

- [ ] **Step 4: Уточнить пустые состояния**

В `renderItems` заменить текст пустого списка:

```javascript
    if (!items || items.length === 0) {
      emptyEl.textContent = currentPath === '/'
        ? 'No folders shared yet — add one in the FerryFile app on your phone'
        : 'This folder is empty';
      emptyEl.style.display = '';
      return;
    }
```

- [ ] **Step 5: Проверить сборку и вручную открыть интерфейс**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

Ассеты не покрываются юнит-тестами, поэтому проверка ручная — она входит в Задачу 11.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "feat(webui): add multi-select downloads, folder download and clearer empty states"
```

---

### Task 11: Финальная проверка и удаление хвостов

**Files:**
- Modify: любые файлы, где остались ссылки на удалённые сущности

**Interfaces:**
- Consumes: всё предыдущее
- Produces: зелёная сборка без мёртвого кода и подтверждённый сквозной сценарий

- [ ] **Step 1: Убедиться, что удалённых сущностей больше нигде нет**

```bash
grep -rn "PreferencesRepository\|SafFileProvider\|PasswordHasher\|zxing\|tryMarkBusy\|markIdle\|hasPassword\|streamProvider\|DownloadHandler\|UploadHandler" \
  app/src gradle/libs.versions.toml app/build.gradle.kts
```

Expected: пусто. Каждое совпадение — либо забытый вызов, либо забытый файл; убрать.

- [ ] **Step 2: Прогнать полную проверку**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`
Expected: BUILD SUCCESSFUL. Предупреждения lint о deprecated `prefs.edit()` допустимы — это стиль существующего кода.

- [ ] **Step 3: Сквозная ручная проверка на устройстве**

Установить сборку и пройти сценарий целиком:

```bash
./gradlew :app:installDebug --console=plain
```

1. Открыть приложение → Settings → Add Folder → выбрать папку. Убедиться, что она появилась в списке с читаемым именем.
2. Вернуться назад **штатной стрелкой в шапке** — проверить, что это обычная иконка, а не текст «← Back», и что системная кнопка «назад» работает так же.
3. Start Server. На главном экране должен быть крупный адрес `http://<ip>:<порт>` и шестизначный PIN. QR-кода быть не должно.
4. Открыть адрес в браузере ПК → ввести PIN → попасть в список файлов.
5. **Скачать один файл** — проверить, что скачался именно он, с исходным именем и расширением, а не `ferryfile.zip`. Отдельно проверить файл с кириллическим именем.
6. **Скачать папку** кнопкой Download в строке папки — проверить, что пришёл `<имя папки>.zip` и внутри сохранена вложенность.
7. **Выделить несколько файлов** чекбоксами → Download → проверить `ferryfile-selection.zip`.
8. Вернуться в корень: убедиться, что кнопка загрузки скрыта и видна подсказка открыть папку.
9. Войти в папку и **загрузить файл с ПК** — перетаскиванием и через кнопку. Проверить: полоса прогресса двигается и доходит до 100%, появляется тост об успехе, файл появляется в списке и физически лежит в папке на телефоне.
10. Загрузить крупный файл (от 100 МБ) — убедиться, что проценты и ETA осмысленные, а интерфейс не подвисает.
11. Открыть вторую вкладку браузера и запустить загрузку там: прогресс первой вкладки не должен реагировать на чужую передачу, ответа 409 быть не должно.
12. Свернуть приложение → в шторке нажать **Stop** в уведомлении → убедиться, что сервер остановился, а на главном экране после возврата статус «Stopped».
13. Снова запустить сервер — PIN должен смениться, старый PIN в браузере не должен подходить.

- [ ] **Step 4: Коммит при необходимости**

Если по итогам проверки что-то правилось:

```bash
git add -A
git commit -m "fix: address issues found during end-to-end verification"
```
