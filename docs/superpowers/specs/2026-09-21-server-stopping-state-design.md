# FerryFile: промежуточное состояние остановки сервера

Дата: 2026-09-21
Ветка: `feature/server-stopping-state`
Статус: утверждено к реализации

## 1. Контекст и проблема

Между `Running` и `Stopped` в UI нет видимого переходного состояния остановки:

- `domain/model/ServerState.kt` содержит только `Stopped`, `Starting`, `Running`.
- `ServerRepositoryImpl.stop()` публикует `Stopped` только после того, как
  `KtorServer.stop()` полностью завершится. Graceful-shutdown Netty занимает до
  ~6 секунд (`gracePeriodMillis = 1_000`, `timeoutMillis = 5_000`), и всё это время
  экран показывает `Running` с адресом и PIN — пользователь не понимает, что
  остановка выполняется.
- Остановка из уведомления идёт напрямую через `FileServerService.processStop()` →
  `ktorServer.stop()` и вообще не проходит через публичный stop репозитория,
  поэтому промежуточное состояние на этом пути тем более не публикуется.

Промежуточное состояние запуска (`Starting`) уже существует и оформлено в
дизайн-доке Broadsheet (вариант `1e`,
`docs/superpowers/specs/2026-09-16-broadsheet-restyle/design/FerryFile.dc.html`,
экран «Запуск…»): dateline-статус, приглушённый заголовок, indeterminate
прогресс-рейка 3dp под head pair, отключённая кнопка. Для остановки используется
тот же визуальный паттерн; отдельного макета «Остановка…» в доке нет — спека
достраивает его симметрично.

## 2. Целевая модель

```kotlin
sealed interface ServerState {

    data object Stopped : ServerState

    data object Starting : ServerState

    data object Stopping : ServerState

    data class Running(
        val address: ServerAddress?,
        val pin: AccessPin,
        val certificateFingerprint: String = ""
    ) : ServerState
}
```

`Stopping` — `data object`, без payload: адрес/PIN/fingerprint в переходном
состоянии не показываются и не должны «дожить» до конца остановки в модели.

## 3. Переходы

| Событие | Переход |
|---|---|
| Принят Start | `Stopped → Starting` |
| Сервис подтвердил bind (`refresh()` после `ktorServer.start()`) | `Starting → Running` |
| Ошибка prepareTls / dispatch / bind | `Starting → Stopped` |
| UI Stop, notification Stop, `onDestroy` сервиса | `Running → Stopping` |
| `KtorServer.stop()` завершился, cleanup выполнен | `Stopping → Stopped` |
| Stop принят, пока Start висит в очереди команд сервиса | устаревший Start не публикует `Running` |
| Повторный Start, пока `Starting` | no-op |

Инварианты:

1. `Stopping` публикуется до вызова физического `server.stop()`.
2. `Stopped` публикуется только после возврата `server.stop()` и отзыва PIN,
   в `finally` — в том числе при исключении shutdown.
3. `Running` публикуется только после фактического bind (существующее поведение
   `Starting`).
4. Операции stop сериализованы тем же `Mutex`, что и start/refresh: параллельные
   Start/Stop/refresh не могут вклиниться в середину `Stopping → Stopped`.
5. `KtorServer` менять не нужно: его `lifecycleMutex` и `engine = null` до
   блокирующего shutdown остаются как есть; `isRunning` по-прежнему отражает
   наличие engine handle.

## 4. Два пути остановки, один общий код

В `ServerRepository` добавляется service-origin метод:

```kotlin
interface ServerRepository {
    val state: StateFlow<ServerState>
    suspend fun start()
    suspend fun stop()
    /** Остановка, инициированная самим сервисом (кнопка Stop в уведомлении). */
    suspend fun stopFromService()
    suspend fun refresh()
}
```

- `stop()` (путь UI): под mutex → `Stopping` (только если `server.isRunning`) →
  `server.stop()` → в `finally`: `accessCodes.revoke()`, сброс
  `activeUseHttps`/`activePort`, `Stopped`, `launchService(ACTION_STOP)` —
  порядок «сначала опубликовали Stopped, затем диспатчим сервис» сохраняется
  как сегодня.
- `stopFromService()` (путь уведомления): тот же общий `stopInternal`, но
  **без** `launchService(ACTION_STOP)` — иначе команда STOP вернулась бы в
  сервис петлёй.

`FileServerService.processStop()` перестаёт вызывать `ktorServer.stop()` напрямую
и вместо этого вызывает `serverRepository.stopFromService()`, после чего —
`stopSelfResult(startId)`. Это заменяет старый путь «`ktorServer.stop()` +
`refreshAndStop()`» на «`stopFromService()`, сам публикующий `Stopped`»;
требование AGENTS.md про service-driven публикацию финального `Stopped`
сохраняется, просто методом.

Защита pending Start от «оживления» после принятого Stop — минимальный маркер в
сервисе:

- `@Volatile var stopRequested = false`;
- `onStartCommand(ACTION_START)` сбрасывает флаг в `false` перед `trySend(Start)`;
- `onStartCommand(ACTION_STOP)` ставит `true` перед `trySend(Stop)`;
- `processStart()` выходит до `ktorServer.start()`, если `stopRequested` — queued
  Stop-команда обязательна и дотащит состояние до `Stopped` через
  `stopFromService()`.

Нормальный порядок: `repository.start()` → `repository.stop()` (физически остановил,
опубликовал `Stopped`, диспатчнул `ACTION_STOP`) → `onStartCommand(ACTION_STOP)`
ставит флаг → `processStart` видит флаг и не поднимает сервер заново.

`onDestroy()` (abnormal teardown): после `ktorServer.stop()` — best-effort
`serverRepository.refresh()` с таймаутом (~2 c), чтобы не оставить UI в
`Running`/`Stopping`; в основной `runBlocking(Dispatchers.IO)` без блокирующего
ожидания mutex на неограниченное время.

`refresh()` остаётся operation-aware только через существующий mutex: пока
`Stopping` активен, параллельный `refresh()` ждёт за lock и затем наблюдает
финальный `Stopped` — раньше времени `Stopping` не затирается.

## 5. UI (Home)

Визуальный язык — паттерн запуска из `1e`:

| Элемент | Stopping |
|---|---|
| Dateline status | `Stopping…` / `Остановка…`, цвет `accent700` |
| Headline | `Stopping…` / `Остановка…`, цвет `neutral700` |
| Standfirst | `home_stopping_standfirst` |
| Progress-рейка 3dp под head pair | indeterminate, `accent` на `neutral300` |
| Connection block (адрес/PIN/fingerprint) | скрыт |
| Кнопка | подпись `home_status_stopping`, disabled, label alpha 0.45 |

Поведение:

- `HomeUiState` получает `isStopping: Boolean`; при `ServerState.Stopping`
  выставляется только он (+ сохранение `hasSharedFolders`), `url`/`pin`/
  `certificateFingerprint` — пустые.
- `enabled = !(isStarting || isStopping)`; onClick: запуск только из
  положительного «не running и не переходного», чтобы `Stopping` не трактовался
  как `Stopped`.
- Notice-блок «нет папок» виден во всех состояниях, как сейчас.

Новые строки (обе локализации, по правилам проекта):

| Name | EN | RU |
|---|---|---|
| `home_status_stopping` | Stopping… | Остановка… |
| `home_stopping_standfirst` | Shutting down the server. | Завершаем работу сервера. |

Web UI не меняется: он доступен только при работающем сервере, а во время
остановки браузер просто теряет соединение.

## 6. Тестирование

`ServerRepositoryImplTest` (расширяется):

- `Stopping` публикуется до завершения `server.stop()` (снимок состояния внутри
  `thenAnswer` на `server.stop()`); `Stopped` — только после;
- `stopFromService()` проходит `Stopping → Stopped` и никогда не диспатчит
  `ACTION_STOP`;
- `stopFromService()` при уже мёртвом движке не показывает `Stopping` и
  приводит к `Stopped`;
- повторный `start()` в состоянии `Starting` — no-op (одна `ACTION_START`);
- `Stopped` публикуется даже если `server.stop()` бросил;
- существующие тесты (включая race-тест refresh/start) остаются зелёными.

`HomeViewModelTest` (новый, фейки доменных интерфейсов, без новых зависимостей):

- `Stopping` → `isStopping = true`, `isRunning == false`, `isStarting == false`,
  пустые `url`/`pin`/fingerprint, `hasSharedFolders` сохранён.

Ручная проверка на устройстве (после merge): быстрый тап Stop в UI и в
уведомлении; состояние `Stopping…` с прогресс-рейкой видимо весь shutdown,
кнопка неактивна, затем `Stopped`.

## 7. Вне объёма

- `Failed`/`TimedOut` состояния и отдельная обработка ошибок остановки.
- Отзыв PIN в начале `Stopping` (сейчас — после физического shutdown; не меняем).
- Operation/generation IDs для всех Start-команд и переработка очереди команд.
- Изменение `restartForAddress()` (HTTPS-рестарт при смене адреса остаётся с
  текущей последовательностью `Starting → Running`).
- Изменение `KtorServer`, Ktor-роутов, `AndroidManifest`, web UI, `i18n.js`,
  дизайна уведомления, persisted-состояния после process death.
