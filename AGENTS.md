# FerryFile

Android app (Kotlin + Compose, single Gradle module `:app`, package `ru.kryu.ferryfile`) that runs an embedded Ktor **Netty** HTTP/HTTPS server on the phone for LAN file transfer via a browser. Storage Access Framework (SAF) provides folder access; a foreground service owns server lifecycle.

## Toolchain

- Gradle wrapper 9.3.1, AGP 9.1.1, Kotlin 2.2.10 + KSP, Hilt, Compose BOM. compileSdk/targetSdk 36, minSdk 30, app jvmToolchain 11, Gradle daemon JVM 21 (auto-provisioned via `gradle/gradle-daemon-jvm.properties`).
- Always use `./gradlew`, never a global gradle.

## Commands

- Build: `./gradlew :app:assembleDebug` (or `:app:installDebug` to a device)
- Unit tests: `./gradlew :app:testDebugUnitTest` (~128 local JVM tests, no device needed)
- Single test class: `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.FileRoutesTest"`
- Instrumented smoke test requires a real device/emulator: `./gradlew :app:connectedDebugAndroidTest`
- Release: `./gradlew :app:bundleRelease` — fails unless `RELEASE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD` are set in `keystore.properties` (git-ignored, lives at repo root), Gradle props, or env. See `docs/RELEASE_SIGNING.md`.
- Lint/typecheck are covered by compiling: `./gradlew :app:compileDebugKotlin`, `./gradlew :app:lintDebug`. No ktlint/detekt configured.

## Architecture rules

- `domain/` is pure Kotlin: no `android.*`, `androidx.*`, `io.ktor.*`, or `dagger.*` imports. Implementations of `domain/repository` interfaces live in `data/` (Hilt DI), server code in `server/`, UI in `ui/`.
- The web UI is hand-written static HTML/JS/CSS in `app/src/main/assets/webui/` (login.html, files.html, app.js, style.css, i18n.js), served by `server/routes/FileRoutes.kt`. It is not generated — API changes require editing these assets together with routes.
- Server runtime is Ktor Netty. Do not reintroduce `ktor-server-cio` (upstream `Expect: 100-continue` framing bug; see `docs/KNOWN_ISSUES.md`).
- Upload limits in `FileRoutes.kt` (`formFieldLimit` bounded by declared `Content-Length`, 8 MiB fallback, 256 MiB hard ceiling) are deliberate safety design with long KDoc explaining the trade-offs — read before touching.

## Localization

Every user-visible string needs all three places updated together:
- Android UI: `app/src/main/res/values/strings.xml` **and** `values-ru/strings.xml`
- Web UI: `app/src/main/assets/webui/i18n.js` (parallel `en` and `ru` objects)

## Gotchas

- Debug build has `applicationIdSuffix = ".debug"` → package is `ru.kryu.ferryfile.debug`; adb/logcat/install commands written for `ru.kryu.ferryfile` will target the release install instead.
- `android.util.Log` is not stubbed in JVM tests: calling it from code under test throws "Method ... not mocked". `isReturnDefaultValues` was removed on purpose, so give such code a `logError: (String, Throwable) -> Unit` seam defaulting to `Log.e` (see `configureFileRoutes`, `stopServerForTimeLimit`) and assert on it in tests.
- Version numbers live only in root `gradle.properties` (`APP_VERSION_*`, `APP_VERSION_CODE`); release procedure in `docs/VERSIONING.md`.
- Upload route must keep `catch (CancellationException) { throw }` semantics and drain the raw request channel on failure (see `docs/DEVICE_VERIFICATION.md` fix rounds — regressions here caused silent 75 MiB upload stalls).
- Home screen state refreshes via `LifecycleEventEffect(ON_RESUME)` **and** service-driven `ServerRepository.refresh()` after notification Stop; both are required.
- `keystore.properties`, `*.jks`, `/app/release/` are git-ignored; never commit signing material.

## Conventions

- Never commit directly to `develop` or `main`. Each feature gets its own branch named `feature/<name>`, merged via PR/merge commit.
- Commit messages: short one-line subject, no body (matches history).
- `docs/superpowers/` holds design specs/plans (some tracked); `.superpowers/` is an ignored local workflow dir — treat both as reference, not source of truth for current behavior.
- README conflicts with code in places (e.g. it references a `LICENSE` that doesn't exist); trust code and `docs/` over README.
