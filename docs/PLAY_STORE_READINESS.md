# FerryFile: Google Play readiness

Дата аудита: 2026-09-16

Документ фиксирует состояние проекта и список работ перед публикацией в Google Play. Проверяемый HEAD: `develop` / `9365991` (`fix server lifecycle`). Аудит основан на исходном коде, конфигурации проекта, результатах Gradle-проверок 2026-09-16, release dependency graph, instrumentation smoke test и отчёте ручной проверки на Android 11 из `docs/DEVICE_VERIFICATION.md`.

## Краткий вывод

FerryFile - Android-приложение, которое запускает на телефоне локальный HTTP-сервер для обмена файлами
с компьютером через браузер; HTTPS с самоподписанным сертификатом можно включить в настройках.

Текущая версия - рабочий MVP. 128 локальных unit-тестов, debug-сборка, release AAB и один instrumentation smoke test проходят, но перед production-публикацией нужно устранить следующие основные риски:

1. HTTP используется по умолчанию; включаемый HTTPS использует device-specific self-signed certificate, и браузеру нужно подтвердить сертификат по fingerprint.
2. `dataSync` foreground service рассчитан на постоянную работу, что конфликтует с ограничениями Android 15+ и создаёт высокий риск несоответствия требованиям Google Play к FGS.
3. Серверные сессии отзываются при logout и остановке сервера, но не имеют серверного TTL; cookie не имеет явного SameSite и согласованного TTL.
4. В приложении нет privacy policy, ссылки на неё и экрана About.
5. Не реализован runtime-запрос `POST_NOTIFICATIONS`.
6. Не хватает Compose UI-, instrumentation- и multi-version device-тестов.
7. README всё ещё обещает QR-код, которого нет в текущем UI; корневого `LICENSE` нет.

Историческая проблема Ktor CIO с `Expect: 100-continue` из `docs/KNOWN_ISSUES.md` не относится к текущему production runtime: приложение использует `ktor-server-netty:3.1.3`, а `ktor-server-cio` отсутствует в release runtime classpath. Подробности и оставшиеся upload-ограничения разделены в `KNOWN_ISSUES.md`.

С предыдущего аудита исправлены: отзыв сессии при logout, опциональный HTTPS с fingerprint pairing, обработка крупных upload и ошибок upload, IPv6-форматирование URL, обновление Home после Stop из уведомления, последовательная обработка команд сервиса, локализация Android/web UI, а также release-версионирование, signing validation, R8 и resource shrinking.

## Что уже есть

- Один Gradle-модуль `:app`.
- Jetpack Compose и Hilt.
- Ktor Netty embedded server с опциональным TLS.
- Storage Access Framework для выбора папок.
- Foreground service для работы сервера в фоне.
- Временный шестизначный PIN, UUID-сессии, отзыв сессий и ограничение неудачных попыток.
- Последовательная очередь Start/Stop в foreground service и обновление состояния репозитория после действия из notification.
- Просмотр папок, загрузка файлов и потоковое скачивание ZIP.
- SSE-прогресс загрузки.
- Английская и русская локализация Android-приложения и web UI.
- `EncryptedSharedPreferences` для порта, темы, режима HTTPS и SAF URI.
- Нет облачной синхронизации, рекламных SDK и аккаунтов.
- Не используется `MANAGE_EXTERNAL_STORAGE`.
- `minSdk = 30`, `compileSdk = 36`, `targetSdk = 36`.
- Текущая версия из `gradle.properties`: `versionName = 0.1.0`, `versionCode = 1`.
- Release-конфигурация требует отдельный keystore, включает R8 и удаление неиспользуемых ресурсов.

`targetSdk = 36` соответствует требованию Google Play для новых приложений и обновлений на дату аудита: начиная с 2026-08-31 они должны target Android 16 / API 36 или выше.

## P0: до отправки на ревью

### 1. Защита передачи данных

Текущее состояние:

- `app/src/main/java/ru/kryu/ferryfile/domain/model/ServerAddress.kt` формирует URL с `http://` по умолчанию и `https://` при включенной настройке.
- `app/src/main/java/ru/kryu/ferryfile/server/KtorServer.kt` использует Netty; TLS-коннектор включается только при выборе HTTPS.
- Сертификат генерируется отдельно для установки приложения, сохраняется в приватном хранилище и содержит текущий LAN IP в SAN.
- При HTTPS PIN, session cookie и содержимое файлов шифруются при передаче; fingerprint показывается на Home для проверки первого подключения. В HTTP-режиме по умолчанию эти данные передаются без шифрования.

Оставшиеся задачи:

- Для повторяющихся подключений можно добавить установку локального CA, чтобы убрать предупреждение браузера.
- Указать в privacy policy, как и куда передаются файлы.
- При смене адреса активной сети HTTPS-сервер обнаруживает несовпадение SAN и автоматически перезапускается с сертификатом для нового адреса. Для HTTP сертификат не используется.

### 2. Foreground service и длительность работы

Текущее состояние:

- `app/src/main/AndroidManifest.xml` объявляет `foregroundServiceType="dataSync"`.
- `FileServerService` запускает сервер после нажатия Start, обрабатывает Start/Stop через последовательную очередь и оставляет его работать до остановки пользователем.
- Для Android 15+ `dataSync` имеет лимит шесть часов за 24 часа фоновой работы.
- `FileServerService` не реализует `onTimeout()`.
- Для target Android 14+ Google Play требует валидный тип FGS, декларацию в Play Console, пользовательский и заметный сценарий, возможность остановки и работу только необходимое время. Исключение для `dataSync` в policy относится к Play Asset Delivery, а не к локальному файловому серверу.

Текущий постоянно доступный локальный HTTP-сервер имеет высокий policy-риск: одной декларации `dataSync` недостаточно. Нужно выбрать и реализовать одну политику:

- Ограниченная серверная сессия с таймером и автоостановкой при бездействии.
- Работа сервера только во время явно инициированной пользователем передачи или другой модели, которая удовлетворяет policy.
- Обоснованный другой тип FGS, только если он действительно соответствует сценарию; замена типа ради обхода лимита недопустима.

В любом варианте нужно реализовать `onTimeout()`, корректное состояние после принудительной остановки, понятное уведомление и проверить сценарий на Android 15/16. В Play Console потребуется декларация FGS, описание пользовательского сценария, последствия прерывания и видео демонстрации.

### 3. Сессии и авторизация

Что уже исправлено:

- `app/src/main/java/ru/kryu/ferryfile/server/routes/AuthRoutes.kt:60-63` отзывает токен на logout и очищает cookie.
- `KtorServer.start()` и `KtorServer.stop()` сбрасывают все in-memory сессии.
- `AuthRoutesTest` проверяет, что повторное использование cookie после logout получает `401`.

Оставшиеся проблемы:

- `app/src/main/java/ru/kryu/ferryfile/server/auth/SessionManager.kt:16-26`: токен не имеет TTL и считается валидным до logout, остановки или перезапуска процесса.
- Cookie задаёт `HttpOnly` и `Path`, а `Secure` включается только для выбранного HTTPS-режима; `SameSite` и TTL явно не настроены. В сохранённой device-проверке наблюдался `Max-Age=604800`, но этот срок не задан кодом и не ограничивает срок жизни серверного токена.
- Ограничение попыток применяется только по IP: три ошибки блокируют адрес на 30 секунд; нет общего лимита и лимита активных сессий.

Нужно:

- Добавить TTL и очистку истёкших сессий.
- Настроить `SameSite` и серверный TTL cookie/session.
- Добавить общий лимит попыток, лимит активных сессий и более заметную защиту от перебора PIN.
- Рассмотреть более длинный код или подтверждение нового устройства.

### 4. Уведомления

`POST_NOTIFICATIONS` объявлен в manifest, но runtime-запрос в коде не найден.

Нужно:

- Запрашивать разрешение до запуска сервера на Android 13+.
- Показать объяснение, что уведомление необходимо для контроля работающего локального сервера.
- Обработать отказ: пользователь должен видеть состояние сервера и способ его остановить.
- Вынести название канала и текст уведомления в ресурсы.

## P1: функциональная надёжность и безопасность

### Жизненный цикл сервера

- `KtorServer.stop()` всё ещё может ждать завершения Netty до пяти секунд, но теперь выполняет блокирующий shutdown в `NonCancellable + Dispatchers.IO`, не блокируя main thread.
- `FileServerService` сериализует Start/Stop, а после Stop из notification вызывает `ServerRepository.refresh()`; это закрывает прежнее окно со stale-состоянием Home.
- Реализовать корректный `onTimeout()` для Android 15+.
- Ошибка запуска Ktor не переводит UI в отдельное состояние ошибки: нужно проверить занятый порт, отказ запуска и сбой foreground service.
- Покрыть process death, повторный запуск, остановку из notification и сбой запуска.
- После process death показывать корректное состояние, не выдавая старый PIN или URL.

Остановка из notification уже исправлена: `FileServerService` обновляет `ServerRepository`, а Home повторно читает состояние. Ручная проверка подтверждает корректный `Stopped` даже когда notification shade открыта поверх уже видимого Home.

### Порт и сетевой адрес

- Сейчас изменение порта во время работы сохраняется в настройках, но работающий сервер не перезапускается.
- Нужно либо запрещать изменение порта при активном сервере, либо автоматически перезапускать его.
- `WifiNetworkRepository` выбирает первый не-loopback IPv4-адрес активной сети. Это может быть VPN, hotspot, Ethernet или другой интерфейс, а не ожидаемый Wi-Fi.
- Нужно показывать доступные интерфейсы или явно сообщать, какой адрес выбран.
- Форматирование IPv6-адреса в URL с квадратными скобками уже реализовано и покрыто unit-тестом, но `WifiNetworkRepository` по-прежнему выбирает только IPv4; обнаружение IPv6 нужно добавить отдельно.
- `0.0.0.0` расширяет область прослушивания на все интерфейсы; нужно оценить ограничение доступа локальными адресами.

### SAF и файлы

- Обрабатывать отзыв постоянного SAF-разрешения и предлагать выбрать папку повторно.
- Добавить проверку свободного места до и во время upload.
- Ограничить общий размер upload, число файлов и число одновременных передач.
- Удалять частично записанный файл при ошибке или явно показывать частичный результат.
- Определить политику конфликтов имён: overwrite, rename или подтверждение.
- Добавить отмену передачи и корректно закрывать потоки при disconnect.
- Проверить архивирование больших каталогов, недоступных дочерних папок и файлов нулевого размера.

Крупная загрузка больше стандартного лимита Ktor исправлена: `FileRoutes.kt` ограничивает multipart-часть по объявленному размеру запроса, fallback равен 8 MiB, жёсткий ceiling - 256 MiB на одну часть; файл при этом потоково пишется на диск. Это не общий лимит всего multipart-запроса: лимиты общего размера, числа файлов и параллельных передач на сервере ещё не заданы. Unit-тест пересекает старый 50 MiB предел, а ручная проверка 75 MiB прошла с совпадающей контрольной суммой. Ограничение 256 MiB, проблемы со свободным местом и частичным файлом остаются.

### Web UI и HTTP-защита

- Добавить `Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy` и подходящий `Cache-Control`.
- Для страниц и API с сессией исключить кэширование чувствительных ответов.
- Web UI уже отбрасывает устаревшие ответы `/api/list` после быстрого перехода между папками.
- Web UI запрещает второй upload, пока первый активен; серверного лимита для прямых клиентов и межсессионной конкуренции пока нет.
- Историческая проблема `Expect: 100-continue` относится к CIO и не используется текущим Netty runtime; обновление Ktor из-за неё не требуется. Для поддерживаемых внешних scripted-клиентов совместимость Netty всё равно стоит отдельно проверить на устройстве.
- Учесть, что клиент без `Content-Length` получает fallback-лимит 8 MiB на multipart part; это также описано в `docs/KNOWN_ISSUES.md`.

## P1: privacy и требования Google Play

### Privacy policy

Нужно разместить публичную privacy policy на стабильном HTTPS URL и добавить ссылку:

- в Play Console;
- внутри приложения, например в Settings/About;
- при необходимости на странице web UI.

Сейчас такой URL и экран About отсутствуют.

Документ должен честно описывать:

- доступ к выбранным пользователем папкам;
- локальное зашифрованное хранение порта, темы и SAF URI через `EncryptedSharedPreferences`;
- временный PIN, in-memory session token, browser cookie и IP-адрес для защиты от перебора;
- передачу файлов между телефоном и браузером по локальной сети только по явному действию пользователя; по умолчанию используется незашифрованный HTTP, а HTTPS можно включить в настройках;
- отсутствие облачного хранения и аналитики, если это действительно так;
- используемые сторонние библиотеки и их обработку данных;
- срок хранения и удаление локальных данных.

### Data Safety

Заполнить Data Safety Form в соответствии с фактическим поведением release-сборки и всеми SDK. Даже если разработчик не получает данные на свой сервер, форму всё равно нужно заполнить для опубликованного приложения.

Если в приложении нет аккаунтов и удалённых пользовательских данных, это нужно явно указать. Не заявлять `no data collected` без проверки всех SDK и сетевых сценариев.

### App content и ревью

В Play Console подготовить:

- FGS declaration для `dataSync` или выбранного альтернативного типа.
- App access instructions для ревьюера.
- Content rating.
- Target audience.
- Ads declaration.
- Privacy policy URL.
- Описание того, что для проверки нужен второй компьютер/телефон в той же локальной сети.
- Инструкции: выдать доступ к папке через системный picker, запустить сервер, ввести динамический PIN и открыть URL с второго устройства.
- Видео или demo mode, позволяющие проверить основной сценарий без заранее известного PIN.
- Ясное описание, что это локальный файловый сервер, а не прокси для третьих лиц; доступ к файлам появляется только после действия пользователя.

Для новых личных developer accounts, созданных после 2023-11-13, перед production потребуется closed test минимум с 12 тестерами, непрерывно opted-in не менее 14 дней.

## P1: UX, локализация и документация

- Android-тексты и notification уже вынесены в `strings.xml`; web UI локализован на английский и русский.
- Проверить локализацию, `contentDescription` и полный пользовательский сценарий на release-сборке.
- Добавить onboarding: выбор папки, объяснение локальной сети, PIN и границ доступа.
- Явно показать предупреждение о доступности файлов всем, кто получает URL/PIN.
- Добавить copy URL, copy PIN и, возможно, QR-код после стабилизации HTTPS/pairing.
- Добавить экран About с версией, privacy policy, лицензией и контактами поддержки. Версия уже отображается в Settings, но отдельного About нет.
- Показывать понятные ошибки при отсутствии сети, занятом порте, отзыве SAF-разрешения и отказе уведомлений.
- Синхронизировать README и store listing с реальным поведением: README всё ещё упоминает QR-код, которого нет в UI; PIN и Netty в README уже соответствуют коду. Формулировки `Nothing leaves your WiFi` и постоянной доступности сервера нужно дополнить оговорками про `0.0.0.0`, выбранный интерфейс и FGS policy.
- README заявляет MIT-лицензию, но корневой файл `LICENSE` в проекте не найден. Добавить его или исправить заявление о лицензии.

## P2: release engineering и тестирование

### Release build

- Текущая конфигурация - `versionName = 0.1.0`, `versionCode = 1`; перед каждой загрузкой в Play нужно осознанно увеличить `versionCode`.
- `isMinifyEnabled = true` и `isShrinkResources = true` уже включены.
- `validateReleaseSigning` требует отдельный keystore и не допускает fallback на debug key.
- 2026-09-16 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:bundleRelease --console=plain` прошёл: выполнены 128 unit-тестов, signing validation, `lintVitalRelease`, R8 и подписывание `app/build/outputs/bundle/release/app-release.aab`.
- В Play Console всё ещё нужно подключить Play App Signing, проверить upload key и загрузить именно release AAB.
- Проверить отсутствие debug-конфигурации, тестовых bypass и лишних логов на release-артефакте.
- Проверить размер AAB/APK и содержимое через APK Analyzer.
- Проверить R8-сборку на физическом устройстве: запуск, сервер, SAF, локализацию и web UI.
- Проанализировать transitive dependencies, особенно старые AndroidX и alpha-версию `security-crypto`.

### Backup

Сейчас `android:allowBackup="true"`, а `backup_rules.xml` и `data_extraction_rules.xml` фактически оставлены шаблонными. Настройки лежат в `EncryptedSharedPreferences`, сохранённые SAF URI могут стать недействительными после restore, а HTTPS-ключ хранится в приватном файле `ferryfile-server.p12` и тоже требует явной backup-политики.

Нужно определить стратегию:

- либо явно исключить encrypted preferences и SAF URI из cloud/device backup;
- либо реализовать проверку и безопасное восстановление разрешений после restore.

Восстановленный SAF URI может больше не иметь действующего разрешения и создавать ложное ощущение доступности папки.

### Тесты

Уже есть и проходят:

- 128 unit/server tests для PIN, revoke/logout, rate limit, маршрутов, path traversal, HTTPS-сертификата, upload, ошибки upload, архивации и конкурентного состояния репозитория;
- регрессионный unit-тест upload за старым 50 MiB лимитом Ktor;
- ручная проверка на Android 11: основной сценарий, upload 75 MiB после исправления и Stop из notification.
- `./gradlew :app:connectedDebugAndroidTest --console=plain`: один instrumentation smoke test на OnePlus Nord N10 / Android 11; он проверяет только базовый context/package и не заменяет функциональные device-тесты.

Нужно добавить и прогнать:

- unit-тесты TTL, `SameSite`, cookie security и лимитов активных сессий;
- расширенные server tests для истёкших/некорректных сессий, replay cookie и upload limits для прямых клиентов;
- Compose UI tests для Start/Stop, Settings и ошибок;
- instrumentation tests для notification permission и foreground service;
- device tests для Android 11, 13, 14, 15 и 16;
- тесты process death, FGS timeout, отказа/отзыва SAF permission и восстановления после backup;
- тесты Wi-Fi, hotspot, VPN, IPv6 и отсутствия сети;
- тесты больших файлов с лимитом 256 MiB, отмены, переполнения диска, частичного файла и разрыва соединения;
- проверку 16 KB page size environment, APK alignment и release-артефакта через APK Analyzer.

## Проверка на 2026-09-16

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:bundleRelease --console=plain` - `BUILD SUCCESSFUL`; 128 unit-тестов, 0 failures, 0 errors; прошли `validateReleaseSigning`, `lintVitalRelease`, R8 и подписывание AAB.
- `./gradlew :app:connectedDebugAndroidTest --console=plain` - `BUILD SUCCESSFUL`; 1 smoke test на `BE2029 - 11`.
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain` - production runtime содержит `ktor-server-netty:3.1.3`; `ktor-server-cio` не разрешается.
- Артефакт создан в `app/build/outputs/bundle/release/app-release.aab`. Это локально подписанный release bundle; подключение Play App Signing и загрузка в Play Console ещё не выполнены.
- Ручная проверка из `docs/DEVICE_VERIFICATION.md` выполнена на OnePlus Nord N10 с Android 11 / API 30. В отчёте есть подтверждение основного сценария, исправления 75 MiB upload и Stop из notification; это историческая запись, а не новый полный end-to-end прогон 2026-09-16.
- Не проверены полноценные Compose UI-тесты, notification permission, FGS timeout, Android 13-16, restore/backup, 16 KB page size и фактическая проверка в Play Console.

## Рекомендуемый порядок работ

- [x] Реализовать опциональный HTTPS с self-signed certificate и fingerprint pairing.
- [ ] Зафиксировать допустимую длительность серверной сессии и соответствие FGS policy.
- [x] Реализовать отзыв сессии на logout и при остановке/перезапуске сервера.
- [ ] Добавить TTL, cookie security и общий/активный rate limit.
- [ ] Реализовать notification permission и стабильное состояние сервиса.
- [x] Исправить обновление Home после Stop из notification.
- [ ] Исправить порт, network address, process death и SAF edge cases.
- [x] Устранить старый 50 MiB stall при upload и добавить failure-path тесты.
- [ ] Добавить общий upload limit, cancellation и partial-file cleanup; отдельно закрепить поведение для chunked-клиентов.
- [x] Зафиксировать Netty как production engine и снять историческую CIO-проблему с текущего release runtime.
- [ ] Подготовить privacy policy и in-app ссылку.
- [ ] Добавить About/onboarding и обновить README, LICENSE и store-facing описание.
- [ ] Добавить release/UI/device-тесты.
- [x] Настроить и локально проверить release AAB, signing validation, R8 и resource shrinking.
- [ ] Настроить backup rules, проверить release на устройствах и подключить Play App Signing.
- [ ] Заполнить Play Console declarations и пройти internal/closed testing.

## Открытые продуктовые решения

1. Нужна ли установка локального CA, чтобы убрать предупреждение браузера после первого pairing?
2. Должен ли сервер работать неограниченно долго или достаточно ограниченной сессии с автоостановкой?
3. Нужен ли demo mode для Play Review без второго устройства?
4. Достаточно ли английского и русского для первой production-версии?

## Официальные источники

- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Data Safety section](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Foreground service and Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
- [Foreground service timeout behavior](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [Testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
