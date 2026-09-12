# FerryFile: исправление передачи файлов, UX и переход на чистую архитектуру

Дата: 2026-09-12
Ветка: `develop`
Статус: утверждено к реализации

## 1. Контекст и проблемы

FerryFile — Android-приложение, поднимающее локальный HTTP-сервер (Ktor CIO) для обмена
файлами между телефоном и браузером на ПК в одной Wi-Fi-сети. Доступ к файлам — через SAF
(`DocumentFile`), аутентификация — паролем, веб-интерфейс — vanilla JS без зависимостей.

Обнаруженные дефекты (с корневыми причинами, установленными по коду):

### 1.1. Загрузка ПК → телефон не работает (три независимых бага)

1. `FileRoutes.kt:172` отвечает `call.respond(OK, mapOf("files" to filesWritten, "bytes" to totalBytes))`.
   Значения имеют разные типы (`Int` и `Long`), Ktor не может подобрать сериализатор для такой
   Map и падает — клиент получает 500 и показывает «Upload failed: 500».
2. `SafFileProvider.resolve("/")` всегда возвращает `null`: после `trim('/')` список сегментов
   пуст. Корень `/` — это виртуальный список расшаренных папок, а не реальная директория.
   Web UI стартует именно на `currentPath = '/'`, поэтому `createFileInPath("/", …)` возвращает
   `null` и файлы молча пропускаются (`filesWritten` остаётся 0).
3. Роут upload никогда не эмитит `TransferEvent.Done` — прогресс-бар висит вечно, список файлов
   не обновляется. Прогресс эмитится с `total = -1, pct = 0`, то есть полоса не двигается даже
   при успешной записи.

Сопутствующее: `connectSSE()` поднимает `EventSource` асинхронно уже после старта `fetch`,
а `MutableSharedFlow` не имеет `replay` — первые события передачи теряются. Используется
deprecated в Ktor 3 `PartData.FileItem.streamProvider()`. Запись идёт без переключения на
`Dispatchers.IO`.

### 1.2. Одиночный файл скачивается архивом

`FileRoutes.kt:110-122` всегда отвечает `respondOutputStream(ContentType.Application.Zip)`
с именем `ferryfile.zip` — одинаковым для любого файла. При этом единственный случай, где ZIP
уместен, не поддержан вовсе: `resolve(path)?.takeIf { !it.isDirectory }` отдаёт 404 на директорию.

### 1.3. Нестандартная кнопка «Назад»

`SettingsScreen.kt:70-72` — `TextButton` с текстом `"← Back"` вместо `TopAppBar` с
`IconButton(Icons.AutoMirrored.Filled.ArrowBack)`. На Home та же проблема: «Settings» текстом
вместо иконки, `TopAppBar` нет ни на одном экране.

### 1.4. Нарушения чистой архитектуры

- Нет ни одного интерфейса: `PreferencesRepository`, `SafFileProvider`, `KtorServer`,
  `SessionManager` — конкретные классы, инжектируемые напрямую.
- Слоя domain нет. ViewModel'и зависят от `data` и `server`: `SettingsViewModel` тянет
  `server.auth.PasswordHasher`, `HomeViewModel` тянет `server.KtorServer`.
- Android-инфраструктура внутри ViewModel: `HomeViewModel.kt:100-134` сам получает IP через
  `ConnectivityManager`/`WifiManager` и рисует QR через zxing; `SettingsViewModel.kt:72-95`
  дёргает `contentResolver.takePersistableUriPermission`.
- Ktor-роуты зависят от конкретного `SafFileProvider` и от `AssetManager`.
- Бизнес-правило «порт в диапазоне 1024..65535» продублировано в Composable и во ViewModel.
- Разбор пути вида `"0/foo/bar"` размазан по `SafFileProvider.resolve` и `isValidPath`.

### 1.5. Проблемы пользовательского опыта

- **QR-код бесполезен в основном сценарии.** У ПК нет камеры; адрес всё равно набирают руками.
- **Пароль — входной барьер.** `Start Server` заблокирован, пока пароль не задан в настройках;
  затем тот же пароль надо набрать в браузере.
- **Нет расшаренных папок — нет объяснения.** Сервер стартует, на ПК пустой список без подсказки.
- **В web UI нельзя выбрать несколько файлов и нельзя скачать папку.**
- **Одна передача за раз.** Глобальный `TransferProgress.tryMarkBusy()` отдаёт 409 на вторую
  передачу; две вкладки браузера выглядят как поломка.
- **Нет кнопки «Стоп» в уведомлении**, хотя `FileServerService.ACTION_STOP` уже реализован.
- HTTP без TLS: учётные данные и cookie идут по локальной сети открытым текстом.

## 2. Принятые решения

| Вопрос | Решение |
|---|---|
| Подключение | Одноразовый 6-значный PIN вместо пароля; QR удаляется вместе с зависимостью zxing |
| Скачивание | Файл — напрямую, папка — ZIP, мультивыбор — ZIP; глобальный лок снимается |
| Архитектура | Слои domain/data/ui/server внутри одного Gradle-модуля `:app`, с use cases |
| Android UX | TopAppBar с иконками, подсказка «нет папок», кнопка «Стоп» в уведомлении |

Вне объёма: журнал передач на главном экране, удаление/переименование/создание папок в web UI,
сортировка и поиск, HTTPS, поддержка HTTP Range, отдельные Gradle-модули.

## 3. Целевая архитектура

### 3.1. Структура пакетов

```
ru.kryu.ferryfile
├── domain
│   ├── model/       FilePath, FileNode, SharedFolder, ServerState, ServerAddress,
│   │                AccessPin, Port, TransferEvent
│   ├── repository/  SettingsRepository, FileStorageRepository, ServerRepository,
│   │                NetworkRepository, AccessCodeRepository        (только интерфейсы)
│   └── usecase/     StartServerUseCase, StopServerUseCase, ObserveServerStateUseCase,
│                    ListDirectoryUseCase, DownloadSelectionUseCase, SaveUploadUseCase,
│                    AddSharedFolderUseCase, RemoveSharedFolderUseCase,
│                    ObserveSharedFoldersUseCase, SetPortUseCase, SetDarkThemeUseCase,
│                    VerifyAccessCodeUseCase
├── data
│   ├── settings/    SettingsRepositoryImpl
│   ├── files/       SafFileStorageRepository, SafPathResolver, SafPermissionManager
│   ├── network/     WifiNetworkRepository
│   ├── server/      ServerRepositoryImpl, InMemoryAccessCodeRepository
│   └── di/          RepositoryModule (@Binds), AppModule (@Provides)
├── server/          KtorServer, routes/, auth/, transfer/
├── ui/              home/, settings/, navigation/, theme/, components/
└── service/         FileServerService
```

**Правило зависимостей:** `ui → domain`, `server → domain`, `data → domain`. Пакет `domain`
не импортирует ничего из `android.*`, `androidx.*`, `io.ktor.*`, `dagger.hilt.android.*`.

Правило закрепляется unit-тестом `DomainLayerPurityTest`, который читает исходники
`app/src/main/java/ru/kryu/ferryfile/domain/**` и падает на запрещённых импортах. Новые
зависимости (Konsist и подобные) не добавляются.

### 3.2. Модели domain

- `@JvmInline value class FilePath(val raw: String)` — путь API-уровня. Фабрика
  `FilePath.parse(String): FilePath?` валидирует формат `<rootIndex>[/segment]*`, отвергает
  `..` и пустые сегменты. Константа `FilePath.ROOT` для виртуального корня.
- `FileNode(path, name, sizeBytes, lastModified, isDirectory, mimeType)`.
- `@JvmInline value class Port(val value: Int)` с `Port.parse(Int): Port?`, диапазон 1024..65535 —
  единственное место, где это правило живёт.
- `AccessPin(val digits: String)` — ровно 6 цифр.
- `ServerAddress(val host: String, val port: Port)` с `asUrl(): String`.
- `SharedFolder(val uri: String, val displayName: String)` — расшаренная через SAF папка.
- `sealed interface ServerState { Stopped; Starting; Running(address: ServerAddress?, pin: AccessPin); }`
  `address == null` означает «нет Wi-Fi».
- `TransferEvent` переезжает в domain: `Progress(transferId, file, bytes, total, pct, etaSeconds)`,
  `Done(transferId, files, bytes)`, `Error(transferId, code, message)`.

### 3.3. Интерфейсы репозиториев

```kotlin
interface SettingsRepository {
    val port: StateFlow<Port>
    val darkTheme: StateFlow<Boolean>
    val sharedFolders: StateFlow<List<SharedFolder>>
    suspend fun setPort(port: Port)
    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun addSharedFolder(uri: String): Boolean
    suspend fun removeSharedFolder(uri: String)
}

interface FileStorageRepository {
    suspend fun listRoot(): List<FileNode>
    suspend fun list(path: FilePath): List<FileNode>?
    suspend fun read(path: FilePath): InputStream?
    suspend fun createFile(dir: FilePath, name: String, mimeType: String): FilePath?
    suspend fun write(path: FilePath): OutputStream?
    suspend fun node(path: FilePath): FileNode?
}

interface NetworkRepository { suspend fun localAddress(): String? }

interface AccessCodeRepository {
    val current: AccessPin?
    fun issue(): AccessPin
    fun revoke()
    fun verify(candidate: String): Boolean   // константное время
}

interface ServerRepository {
    val state: StateFlow<ServerState>
    suspend fun start()
    suspend fun stop()
}
```

`SettingsRepository` реактивен целиком — сейчас реактивна только тема, остальное читается
синхронными геттерами из UI-потока.

### 3.4. Прочие переезды

- Разбор и резолв пути: `SafPathResolver` (data) принимает `FilePath` и отдаёт `DocumentFile`.
  `SafFileProvider` растворяется в `SafFileStorageRepository` + `SafPathResolver`.
- `takePersistableUriPermission` / `releasePersistableUriPermission` — в `SafPermissionManager`,
  ViewModel про `Context` не знает.
- Получение IP — в `WifiNetworkRepository`, вся ветвление по `Build.VERSION.SDK_INT` там же.
- `KtorServer` остаётся инфраструктурным классом; `ServerRepositoryImpl` инкапсулирует запуск
  сервиса, выдачу PIN и вычисление адреса, собирая `ServerState`.
- Ktor-роуты принимают use cases, а не репозитории и не `SafFileProvider`: `configureFileRoutes`
  получает `ListDirectoryUseCase`, `DownloadSelectionUseCase`, `SaveUploadUseCase`;
  `configureAuthRoutes` — `VerifyAccessCodeUseCase`. Отдача статики web UI остаётся на
  `AssetManager`: это ресурс самого HTTP-слоя, а не доменная сущность.

## 4. Аутентификация по PIN

- При `ServerRepository.start()` вызывается `AccessCodeRepository.issue()`: 6 цифр из
  `SecureRandom`, хранятся только в памяти, стираются в `revoke()` при остановке сервера.
- Сверка — `MessageDigest.isEqual` над UTF-8 байтами, чтобы не было timing-утечки.
- Удаляются: `PasswordHasher`, `PasswordHasherTest`, ключ `KEY_PASSWORD_HASH` в настройках,
  секция Password на экране настроек, поле `hasPassword` в `HomeUiState`/`SettingsUiState`.
- `SessionManager` сохраняется как есть: 3 неудачные попытки с одного IP → блокировка на 30 секунд.
  Для 6-значного PIN это делает перебор непрактичным.
- `login.html`: одно поле «Введите PIN с экрана телефона», `inputmode="numeric"`,
  `maxlength="6"`, `autocomplete="off"`. Тело запроса `POST /login` — `{"pin":"123456"}`.
- `Start Server` больше ничем не блокируется.

## 5. Передача файлов

### 5.1. Upload (ПК → телефон)

- Ответ роута — `@Serializable data class UploadResponse(val files: Int, val bytes: Long)`.
- Запись в корень запрещена: `path == FilePath.ROOT` → 400 с телом
  `{"error":"root_not_writable"}`. Web UI не показывает загрузку в корне и объясняет,
  что нужно открыть папку.
- Если расшарена ровно одна папка, web UI при старте сразу открывает её, минуя корень.
- Общий размер берётся из заголовка `Content-Length` запроса; накопленные байты считаются
  по всем частям, отсюда настоящие `pct` и `etaSeconds`.
- После успеха эмитится `TransferEvent.Done(transferId, filesWritten, totalBytes)`;
  при исключении — `TransferEvent.Error(transferId, code, message)` (сейчас ошибки в поток
  не попадают вообще).
- `part.provider()` (`ByteReadChannel`) вместо deprecated `part.streamProvider()`.
- Копирование выполняется в `Dispatchers.IO`.
- Клиент открывает `EventSource` и дожидается события `open`, и только затем отправляет POST —
  иначе первые события теряются на `MutableSharedFlow` без `replay`.

### 5.2. Download (телефон → ПК)

- `GET /api/download?path=<p>`, где `<p>` — файл: ответ потоком, `Content-Type` из MIME
  `DocumentFile`, `Content-Length` из размера, заголовок
  `Content-Disposition: attachment; filename="<ascii-fallback>"; filename*=UTF-8''<percent-encoded>`
  — второй параметр обязателен для кириллических имён.
- `<p>` — директория: ZIP с именем `<имя папки>.zip`, обход рекурсивный, вложенность сохраняется
  в именах записей ZIP.
- Мультивыбор: `GET /api/download?path=a&path=b&…`. Правило именования однозначно:
  ровно один путь и это файл — отдаётся напрямую, без ZIP; ровно один путь и это папка —
  `<имя папки>.zip`; два и более путей — `ferryfile-selection.zip`, независимо от их типа.
- `DownloadHandler` разделяется на `ZipStreamWriter` (существующая логика) и `FileStreamWriter`.

### 5.3. Параллельные передачи

- `TransferProgress.tryMarkBusy()` / `markIdle()` / `isBusy` удаляются; ответы 409 исчезают.
- Каждое событие несёт `transferId` — UUID, который клиент генерирует сам и передаёт как
  query-параметр `transferId` при upload; SSE-клиент отбрасывает чужие события. Это корректно
  работает при нескольких открытых вкладках.
- Прогресс эмитится только при upload. На скачивании браузер показывает собственный индикатор,
  серверный прогресс там не нужен.

## 6. Пользовательский интерфейс

### 6.1. Android

- `TopAppBar` (Material3) на обоих экранах. Settings — навигационная иконка
  `Icons.AutoMirrored.Filled.ArrowBack`; Home — action-иконка `Icons.Default.Settings`.
  Обе иконки есть в `material-icons-core`, новых зависимостей не требуется.
- Home в состоянии Running: карточка с адресом `http://<ip>:<port>` крупным моноширинным шрифтом
  и PIN отдельной строкой тем же начертанием. Если адреса нет — «Нет подключения к Wi-Fi».
- Home при пустом списке папок: карточка «Папки не добавлены» с кнопкой, ведущей в настройки.
  Сервер при этом стартовать можно.
- QR удаляется: `generateQr` из `HomeViewModel`, `zxing-core` из `libs.versions.toml` и
  `app/build.gradle.kts`.
- `HomeViewModel` и `SettingsViewModel` зависят только от use cases, `Context` не инжектируется.
- Валидация порта в Composable удаляется. `SettingsUiState` получает поле
  `portError: PortError?`, которое выставляет `SetPortUseCase` на основании `Port.parse`;
  Composable только отображает его.

### 6.2. Уведомление

- Текст: `http://<ip>:<port>`; при отсутствии Wi-Fi — «Нет подключения к Wi-Fi».
- Действие «Стоп» — `PendingIntent` с уже существующим `FileServerService.ACTION_STOP`
  (флаг `FLAG_IMMUTABLE`).
- PIN в уведомление не выводится: оно видно на экране блокировки.

### 6.3. Web UI

- Чекбокс в каждой строке списка; панель выделения «Выбрано N · Скачать · Снять выделение».
- На строке папки — иконка скачивания (скачивает папку архивом).
- Загрузка и drop-zone скрыты на корневом экране, вместо них подсказка.
- Пустые состояния: «Папки не расшарены — добавьте папку в приложении на телефоне» для корня,
  «Папка пуста» для обычной директории.
- `login.html` переведён на PIN.
- Разметка сохраняет текущий подход: только `textContent`/`setAttribute` для данных с сервера,
  `innerHTML` — исключительно для статических SVG-литералов.

## 7. Тестирование

Разработка по TDD: сначала падающий тест, затем реализация.

Переписываются:
- `AuthRoutesTest` — на PIN: верный PIN выдаёт сессию, неверный — 401, четвёртая попытка — 429.
- `FileRoutesTest` — одиночный файл отдаётся без ZIP с корректным `Content-Disposition`;
  директория отдаётся ZIP; мультивыбор отдаётся ZIP; одиночный файл в мультивыборе — напрямую;
  upload в корень — 400; успешный upload возвращает `UploadResponse` и эмитит `Done`.
- `PreferencesRepositoryTest` → `SettingsRepositoryImplTest`.

Удаляется: `PasswordHasherTest` (вместе с классом).

Добавляются:
- `FilePathTest`, `PortTest`, `SafPathResolverTest` — включая отказ на `..` и пустые сегменты.
- `InMemoryAccessCodeRepositoryTest` — формат PIN, смена PIN при перезапуске, отзыв.
- Тесты use cases на фейковых реализациях репозиториев.
- `DomainLayerPurityTest` — запрещённые импорты в `domain`.
- `UploadHandlerTest` / `ZipStreamWriterTest` — существующие расширяются на прогресс и ошибки.

## 8. Этапы реализации

1. Каркас `domain` (модели, интерфейсы, use cases) и `data` с биндингами Hilt; существующий код
   переезжает за фасады без изменения поведения, все тесты зелёные.
2. Исправление upload: сериализация ответа, запрет корня, `Done`/`Error`, реальный прогресс.
3. Исправление download: файл напрямую, папка ZIP, мультивыбор; снятие глобального лока.
4. Переход на PIN: сервер, `login.html`, главный экран, удаление `PasswordHasher`.
5. Android UI: `TopAppBar` с иконками, карточка адреса и PIN, подсказка «нет папок»,
   действие «Стоп» в уведомлении.
6. Web UI: чекбоксы, мультискачивание, пустые состояния, скрытие загрузки в корне.
7. Удаление мёртвого кода: zxing, остатки парольной схемы, `tryMarkBusy`.

Каждый этап — отдельный коммит с зелёными тестами.
