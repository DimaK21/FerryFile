# Промежуточное состояние остановки сервера — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить видимое переходное состояние `Stopping` между `Running` и `Stopped` для обоих путей остановки (кнопка в UI и кнопка Stop в уведомлении), по образцу существующего `Starting` из дизайн-дока Broadsheet (`1e`).

**Architecture:** `domain/model/ServerState.kt` получает `Stopping`; `ServerRepository` — service-origin метод `stopFromService()`; оба stop-пути сводятся в общий `stopInternal()` под существующим `Mutex` в `ServerRepositoryImpl`; `FileServerService.processStop()` перестаёт останавливать движок в обход репозитория; UI (`HomeViewModel`/`HomeScreen`) отображает `Stopping` тем же паттерном, что и `Starting`. `KtorServer`, DI, роуты, web UI не меняются.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (BOM 2026.02.01), coroutines/StateFlow, Hilt, Ktor Netty (без изменений). Тесты: JUnit 4, mockito-kotlin 5.4.0, kotlinx-coroutines-test 1.9.0 — все уже в classpath, новых зависимостей не добавлять.

**Spec:** `docs/superpowers/specs/2026-09-21-server-stopping-state-design.md`

## Global Constraints

- Ветка: `feature/server-stopping-state` от `develop` (прямые коммиты в `develop`/`main` запрещены). Коммит на задачу, сообщение — одна короткая строка.
- Прогон тестов: `./gradlew :app:testDebugUnitTest`; одиночный класс: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.server.ServerRepositoryImplTest"`.
- `domain/` остаётся чистым (никаких `android.*`/`androidx.*`/`io.ktor.*`/`dagger.*` — `DomainLayerPurityTest`), `kotlinx.coroutines.flow` можно.
- Сохранить существующие инварианты: `Running` публикуется только после подтверждения bind сервисом; `stopped` из `refresh()` (внешняя остановка) публикуется так же, как сегодня; `LifecycleEventEffect(ON_RESUME) { refresh() }` не трогать.
- Не менять `KtorServer.kt`, `server/routes/*`, web UI ассеты, `i18n.js`, `AndroidManifest.xml`, `di/*`.
- Порядок в `stopInternal`: сначала `Stopped` в `_state`, затем (для UI-пути) `launchService(ACTION_STOP)` — как в текущем `finally`.

## Структура файлов

```
app/src/main/java/.../domain/model/ServerState.kt        modify: + Stopping
app/src/main/java/.../domain/repository/ServerRepository.kt  modify: + stopFromService()
app/src/main/java/.../data/server/ServerRepositoryImpl.kt    modify: stopInternal, guard
app/src/main/java/.../service/FileServerService.kt           modify: stopRequested, stopFromService, onDestroy
app/src/main/java/.../ui/home/HomeViewModel.kt               modify: + isStopping
app/src/main/java/.../ui/home/HomeScreen.kt                  modify: Stopping-ветки
app/src/main/res/values/strings.xml                          modify: + 2 строки
app/src/main/res/values-ru/strings.xml                       modify: + 2 строки
app/src/test/java/.../data/server/ServerRepositoryImplTest.kt modify: + 5 тестов
app/src/test/java/.../ui/home/HomeViewModelTest.kt           create
```

---

### Task 1: Domain-модель + репозиторий (TDD)

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/domain/model/ServerState.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/domain/repository/ServerRepository.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/data/server/ServerRepositoryImpl.kt`
- Test: `app/src/test/java/ru/kryu/ferryfile/data/server/ServerRepositoryImplTest.kt`

- [ ] **Step 1: Добавить падающие тесты в `ServerRepositoryImplTest`**

```kotlin
@Test
fun `stop publishes Stopping before the engine shutdown returns`() = runTest {
    whenever(server.isRunning).thenReturn(true)
    val repo = repo(
        network = SequenceNetworkRepository(mutableListOf("10.0.0.5")),
        accessCodes = InMemoryAccessCodeRepository()
    )
    var stateDuringShutdown: ServerState? = null
    whenever(server.stop()).thenAnswer {
        stateDuringShutdown = repo.state.value
        Unit
    }

    repo.stop()

    assertEquals(ServerState.Stopping, stateDuringShutdown)
    assertEquals(ServerState.Stopped, repo.state.value)
}

@Test
fun `service-origin stop never dispatches ACTION_STOP`() = runTest {
    whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
    whenever(settings.useHttps).thenReturn(MutableStateFlow(false))
    whenever(server.isRunning).thenReturn(false, true)
    val repo = repo(
        network = SequenceNetworkRepository(mutableListOf("10.0.0.5")),
        accessCodes = InMemoryAccessCodeRepository()
    )
    repo.start()   // Starting, одна ACTION_START в launches
    repo.refresh() // Running
    var stateDuringShutdown: ServerState? = null
    whenever(server.stop()).thenAnswer {
        stateDuringShutdown = repo.state.value
        Unit
    }

    repo.stopFromService()

    assertEquals(ServerState.Stopping, stateDuringShutdown)
    assertEquals(ServerState.Stopped, repo.state.value)
    assertTrue(repo.launches.none { it.action == FileServerService.ACTION_STOP })
}

@Test
fun `service-origin stop of an already dead server goes straight to stopped`() = runTest {
    whenever(server.isRunning).thenReturn(false)
    val repo = repo(
        network = SequenceNetworkRepository(mutableListOf()),
        accessCodes = InMemoryAccessCodeRepository()
    )
    var stateDuringShutdown: ServerState? = null
    whenever(server.stop()).thenAnswer {
        stateDuringShutdown = repo.state.value
        Unit
    }

    repo.stopFromService()

    assertEquals(ServerState.Stopped, repo.state.value)
    assertNotSame(ServerState.Stopping, stateDuringShutdown)
}

@Test
fun `second start while starting is ignored`() = runTest {
    whenever(settings.port).thenReturn(MutableStateFlow(Port.DEFAULT))
    whenever(settings.useHttps).thenReturn(MutableStateFlow(false))
    whenever(server.isRunning).thenReturn(false)
    val repo = repo(
        network = SequenceNetworkRepository(mutableListOf("10.0.0.5", "10.0.0.5")),
        accessCodes = InMemoryAccessCodeRepository()
    )

    repo.start()
    repo.start()

    assertEquals(1, repo.launches.size)
    assertEquals(ServerState.Starting, repo.state.value)
}

@Test
fun `stop publishes stopped even when the engine shutdown throws`() = runTest {
    whenever(server.isRunning).thenReturn(true)
    whenever(server.stop()).thenThrow(IllegalStateException("shutdown"))
    val accessCodes = InMemoryAccessCodeRepository()
    val repo = repo(
        network = SequenceNetworkRepository(mutableListOf()),
        accessCodes = accessCodes
    )

    val failure = runCatching { repo.stop() }

    assertTrue(failure.isFailure)
    assertEquals(ServerState.Stopped, repo.state.value)
    assertNull(accessCodes.current)
}
```

- [ ] **Step 2: Прогнать — должны падать (нет `Stopping`/`stopFromService`)**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.server.ServerRepositoryImplTest"`
Expected: FAIL — unresolved references `ServerState.Stopping`, `stopFromService`.

- [ ] **Step 3: `ServerState.kt` — добавить состояние**

```kotlin
    data object Starting : ServerState

    /** Идёт graceful shutdown Netty; адрес и PIN уже не показываются. */
    data object Stopping : ServerState
```

- [ ] **Step 4: `ServerRepository.kt` — добавить метод**

```kotlin
    /** Останавливает сервер без повторного диспатча `ACTION_STOP` — вызывается самим сервисом. */
    suspend fun stopFromService()
```

- [ ] **Step 5: `ServerRepositoryImpl.kt` — общий stop-путь и guard**

Заменить `start()`/`stop()` и добавить:

```kotlin
    override suspend fun start(): Unit = mutex.withLock {
        if (server.isRunning) {
            refreshLocked()
            return@withLock
        }
        if (_state.value is ServerState.Starting) {
            return@withLock // дубль Start, пока сервис ещё не подтвердил bind
        }
        // ... существующее тело без изменений ...
    }

    override suspend fun stop(): Unit = mutex.withLock {
        stopInternal(dispatchService = true)
    }

    override suspend fun stopFromService(): Unit = mutex.withLock {
        stopInternal(dispatchService = false)
    }

    private suspend fun stopInternal(dispatchService: Boolean) {
        // Stopping только когда движок реально живёт: stopFromService() может прийти после
        // того, как UI-стоп уже всё уронил, или пока Start висит в очереди команд сервиса.
        if (server.isRunning) {
            _state.value = ServerState.Stopping
        }
        try {
            // Wait for the actual engine shutdown before publishing stopped. This also
            // keeps the blocking Netty shutdown away from the caller's main thread.
            server.stop()
        } finally {
            accessCodes.revoke()
            activeUseHttps = false
            activePort = null
            _state.value = ServerState.Stopped
            if (dispatchService) {
                launchService(FileServerService.ACTION_STOP)
            }
        }
    }
```

`refreshLocked()` и `restartForAddress()` не меняются (обе операции под тем же
mutex; ожидающие `refresh()` увидят только финальный `Stopped`).

- [ ] **Step 6: Прогнать тесты репозитория — весь класс зелёный (новые + существующие)**

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.data.server.ServerRepositoryImplTest"`
Expected: PASS. Если существующий race-тест `a refresh racing a still-starting...`
потребует правок — править ожидания только под новый guard (второй `start()` теперь
no-op), сам сценарий не менять.

- [ ] **Step 7: Прогнать компиляцию UI (when по sealed-state станет неполным)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL на `HomeViewModel.kt: when` — исправить в Task 3 (или сделать его
сейчас минимально, добавив `is ServerState.Stopping -> HomeUiState(isStopping = ...)`
после Task 3; до этого момента допустимо временное ветвление в `else`).

- [ ] **Step 8: Коммит (после Task 3, зелёной сборки)**

```bash
git add app/src/main/java/ru/kryu/ferryfile/domain app/src/main/java/ru/kryu/ferryfile/data/server app/src/test/java/ru/kryu/ferryfile/data/server
git commit -m "Add Stopping server state with service-origin stop"
```

---

### Task 2: FileServerService — остановка через репозиторий

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/service/FileServerService.kt`

- [ ] **Step 1: Флаг принятых остановок**

```kotlin
    // true с момента приёма ACTION_STOP до начала обработки ACTION_START: не даёт
    // устаревшей queued Start-команде поднять сервер после принятой остановки.
    @Volatile
    private var stopRequested = false
```

- [ ] **Step 2: `onStartCommand` — сброс/установка флага**

В ветке `ACTION_START` перед `commands.trySend(ServerCommand.Start(...))`:

```kotlin
                stopRequested = false
```

В ветке `ACTION_STOP`:

```kotlin
            ACTION_STOP -> {
                stopRequested = true
                commands.trySend(ServerCommand.Stop(startId))
            }
```

- [ ] **Step 3: `processStart` — выход по флагу**

```kotlin
    private suspend fun processStart(command: ServerCommand.Start) {
        if (stopRequested) return // queued Stop дотащит состояние до Stopped сам
        if (ktorServer.isRunning) return
        // ... существующее тело без изменений ...
    }
```

- [ ] **Step 4: `processStop` — общий stop через репозиторий**

```kotlin
    private suspend fun processStop(command: ServerCommand.Stop) {
        try {
            serverRepository.stopFromService()
        } catch (cause: Exception) {
            Log.e(LOG_TAG, "Failed to stop server via repository", cause)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stopSelfResult(command.startId)
            }
        }
    }
```

(`refreshAndStop()` остаётся только для failure-путей старта.)

- [ ] **Step 5: `onDestroy` — best-effort reconciliation**

```kotlin
        runBlocking(Dispatchers.IO) {
            try {
                ktorServer.stop()
            } catch (cause: Exception) {
                Log.e(LOG_TAG, "Failed to stop server during service teardown", cause)
            }
            runCatching { withTimeoutOrNull(2_000) { serverRepository.refresh() } }
        }
```

Импорты: `kotlinx.coroutines.withTimeoutOrNull`. Таймаут страхует от ANR: `refresh()`
ждёт mutex репозитория, а UI-операция stop может держать его до ~6 с; при таймауте
аварийного reconciliation состояние добирается `ON_RESUME`-refresh при следующем
открытии приложения.

- [ ] **Step 6: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (после правок Task 3, иначе — только до `HomeViewModel`).

- [ ] **Step 7: Коммит (после зелёной сборки)**

```bash
git add app/src/main/java/ru/kryu/ferryfile/service/FileServerService.kt
git commit -m "Route notification stop through server repository"
```

---

### Task 3: UI — HomeViewModel, HomeScreen, строки

**Files:**
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`
- Test: `app/src/test/java/ru/kryu/ferryfile/ui/home/HomeViewModelTest.kt` (create)

- [ ] **Step 1: Строки (обе локализации рядом)**

`values/strings.xml`:

```xml
    <string name="home_status_stopping">Stopping…</string>
    <string name="home_stopping_standfirst">Shutting down the server.</string>
```

`values-ru/strings.xml`:

```xml
    <string name="home_status_stopping">Остановка…</string>
    <string name="home_stopping_standfirst">Завершаем работу сервера.</string>
```

- [ ] **Step 2: `HomeUiState` + маппинг**

```kotlin
data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val url: String = "",
    val pin: String = "",
    val certificateFingerprint: String = "",
    val hasWifi: Boolean = true,
    val hasSharedFolders: Boolean = true
) {
    val isBusy: Boolean get() = isStarting || isStopping
}
```

в `combine` добавить ветку:

```kotlin
                is ServerState.Stopping -> HomeUiState(
                    isStopping = true,
                    hasSharedFolders = folders.isNotEmpty()
                )
```

- [ ] **Step 3: `HomeViewModelTest` (новый файл, фейки доменных интерфейсов)**

```kotlin
package ru.kryu.ferryfile.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.ServerState
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.ServerRepository
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import ru.kryu.ferryfile.domain.usecase.ObserveServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.ObserveSharedFoldersUseCase
import ru.kryu.ferryfile.domain.usecase.RefreshServerStateUseCase
import ru.kryu.ferryfile.domain.usecase.StartServerUseCase
import ru.kryu.ferryfile.domain.usecase.StopServerUseCase

class HomeViewModelTest {

    private class FakeServerRepository(initial: ServerState) : ServerRepository {
        private val _state = MutableStateFlow(initial)
        override val state = _state.asStateFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun stopFromService() {}
        override suspend fun refresh() {}
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val port = MutableStateFlow(Port.DEFAULT)
        override val darkTheme = MutableStateFlow(false)
        override val useHttps = MutableStateFlow(false)
        override val sharedFolders =
            MutableStateFlow(listOf(SharedFolder("content://tree/a", "a")))
        override suspend fun setPort(port: Port) {}
        override suspend fun setDarkTheme(enabled: Boolean) {}
        override suspend fun setUseHttps(enabled: Boolean) {}
        override suspend fun addSharedFolder(uri: String) = true
        override suspend fun removeSharedFolder(uri: String) {}
    }

    private fun viewModel(state: ServerState) = HomeViewModel(
        ObserveServerStateUseCase(FakeServerRepository(state)),
        ObserveSharedFoldersUseCase(FakeSettingsRepository()),
        StartServerUseCase(FakeServerRepository(state)),
        StopServerUseCase(FakeServerRepository(state)),
        RefreshServerStateUseCase(FakeServerRepository(state))
    )

    @Test
    fun `stopping maps to a busy UI state without credentials`() = runTest {
        val uiState = viewModel(ServerState.Stopping).uiState.first { it.isStopping }

        assertTrue(uiState.isStopping)
        assertTrue(uiState.isBusy)
        assertFalse(uiState.isRunning)
        assertFalse(uiState.isStarting)
        assertEquals("", uiState.url)
        assertEquals("", uiState.pin)
        assertEquals("", uiState.certificateFingerprint)
        assertTrue(uiState.hasSharedFolders)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.ui.home.HomeViewModelTest"`
Expected: PASS после шагов 2–3 (before — падает на компиляции).

- [ ] **Step 4: `HomeScreen` — ветки Stopping (паттерн 1e)**

- Dateline status: в `when` добавить `uiState.isStopping -> stringResource(R.string.home_status_stopping)`;
  цвет: `if (uiState.isRunning || uiState.isBusy) colors.accent700 else colors.neutral700`.
- Progress-рейка: `if (uiState.isBusy) { LinearProgressIndicator(...) }`.
- Headline: `uiState.isStopping -> home_status_stopping`; цвет заголовка:
  `if (uiState.isBusy) colors.neutral700 else colors.text`.
- Standfirst: `uiState.isStopping -> home_stopping_standfirst`.
- Кнопка: `enabled = !uiState.isBusy`; onClick — старт только если `!uiState.isRunning
  && !uiState.isBusy` (иначе no-op), stop если `isRunning`; `displayLabel`:

```kotlin
                val displayLabel = when {
                    uiState.isStarting -> stringResource(R.string.home_status_starting)
                    uiState.isStopping -> stringResource(R.string.home_status_stopping)
                    uiState.isRunning -> stringResource(R.string.home_stop_server)
                    else -> stringResource(R.string.home_start_server)
                }
                Text(
                    text = displayLabel,
                    style = BroadsheetType.buttonLabel,
                    modifier = if (uiState.isBusy) Modifier.alpha(0.45f) else Modifier
                )
```

- Блок `if (uiState.isRunning)` (connection) и `isNoWifi` не трогаем: в
  `isStopping` они и так не рисуются.

- [ ] **Step 5: Прогон всех юнит-тестов и компиляция**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL, все зелёные.

- [ ] **Step 6: Коммит (вместе с Task 1, или отдельно если не сделано)**

```bash
git add app/src/main/java/ru/kryu/ferryfile/ui/home app/src/main/res app/src/test/java/ru/kryu/ferryfile/ui
git commit -m "Show Stopping state on Home"
```

---

### Task 4: Финальная проверка и устройство

- [ ] **Step 1: Линт и полная сборка**

```bash
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Ручные сценарии на устройстве (эмулятор не подходит — нужен Wi-Fi)**

1. Запуск → `Запуск…` с прогресс-рейкой → `Сервер работает` (регресс существующего
   поведения).
2. Стоп кнопкой в UI: во время всего shutdown (Netty может ждать до ~6 с) виден
   экран `Остановка…` с прогресс-рейкой, без адреса/PIN, кнопка неактивна →
   `Сервер остановлен`.
3. Стоп кнопкой Stop в уведомлении при открытом приложении: тот же `Остановка…`
   → `Сервер остановлен`; уведомление исчезает; повторный `ACTION_STOP`-штормов
   нет (в logcat — одиночный STOP-команд).
4. Быстрый тап Stop во время `Запуск…` (Start ещё в очереди сервиса): финальное
   состояние `Остановлен`, сервер не поднимается.
5. Языки EN/RU: проверить экраны состояний; RU-строка `Остановка…` в dateline не
   должна ломать сетку (10sp small-caps).
6. Перезапуск приложения после abnormal kill сервиса (swipe приложения): с
   `ON_RESUME`-refresh домашний экран показывает актуальное состояние.

- [ ] **Step 3: Merge по конвенции — только через PR/merge commit в `develop`, не напрямую**

---

## Self-review checklist (перед PR)

- [ ] `DomainLayerPurityTest` зелёный (в `ServerState`/`ServerRepository` нет запрещённых импортов).
- [ ] Ни одного вызова `ktorServer.stop()` из сервисных команд кроме `onDestroy`.
- [ ] `launchService(ACTION_STOP)` вызывается ровно из UI-stop.
- [ ] Тест `stop waits for the engine shutdown before publishing stopped` (существовавший) зелёный.
- [ ] Коммиты — короткие однострочные, без тела.
