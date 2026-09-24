# Contributing

## Build and test

Always use the Gradle wrapper (`./gradlew`). The daemon JVM is provisioned automatically
(`gradle/gradle-daemon-jvm.properties`); the Android SDK location comes from `local.properties`
(`sdk.dir`) or `ANDROID_HOME`.

| Task | Command |
| --- | --- |
| Debug build | `./gradlew :app:assembleDebug` (or `:app:installDebug` to a device) |
| Unit tests (local JVM, no device) | `./gradlew :app:testDebugUnitTest` |
| One test class | `./gradlew :app:testDebugUnitTest --tests "ru.kryu.ferryfile.server.routes.FileRoutesTest"` |
| Compile / lint | `./gradlew :app:compileDebugKotlin :app:lintDebug` |
| Instrumented smoke test | `./gradlew :app:connectedDebugAndroidTest` (needs a device or emulator) |
| Signed release bundle | `./gradlew :app:bundleRelease` — see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) |

There is no ktlint or detekt; compiling and `lintDebug` are the checks.

## Project layout

Single Gradle module `:app`, package `ru.kryu.ferryfile`.

- `domain/` — pure Kotlin: no `android.*`, `androidx.*`, `io.ktor.*` or `dagger.*` imports.
  Repository interfaces live here; their implementations live in `data/` (Hilt DI).
- `server/` — the embedded Ktor server and its routes.
- `ui/` — Jetpack Compose screens.
- `app/src/main/assets/webui/` — the browser UI (`login.html`, `files.html`, `app.js`,
  `style.css`, `i18n.js`). It is hand-written static HTML/JS/CSS served by
  `server/routes/FileRoutes.kt`, not generated: an API change means editing these assets together
  with the routes.

## Rules

- The server runs on Ktor **Netty**. Do not reintroduce `ktor-server-cio` (upstream
  `Expect: 100-continue` framing bug, see [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md)).
- Upload limits in `FileRoutes.kt` (`formFieldLimit` bounded by the declared `Content-Length`,
  8 MiB fallback, 256 MiB hard ceiling) are deliberate. Read the KDoc there before touching them.
- The upload route must keep `catch (CancellationException) { throw }` semantics and drain the
  raw request channel on failure; regressions there caused silent upload stalls.
- Home screen state refreshes both via `LifecycleEventEffect(ON_RESUME)` **and** via the
  service-driven `ServerRepository.refresh()` after a notification Stop. Both are required.
- Never commit signing material: `keystore.properties`, `*.jks` and `/app/release/` are
  git-ignored.

### Localization

Every user-visible string must be added in all three places:

- Android UI: `app/src/main/res/values/strings.xml` **and** `app/src/main/res/values-ru/strings.xml`
- Web UI: `app/src/main/assets/webui/i18n.js` (parallel `en` and `ru` objects)

### Testing notes

- `android.util.Log` is not stubbed in JVM tests; calling it from code under test throws
  `Method ... not mocked`. Give such code a `logError: (String, Throwable) -> Unit` parameter that
  defaults to `Log.e` (see `configureFileRoutes`, `stopServerForTimeLimit`) and assert on it in
  tests. Do not re-enable `isReturnDefaultValues`.
- The debug build has `applicationIdSuffix = ".debug"`, so its package is
  `ru.kryu.ferryfile.debug`. `adb`/`logcat` commands written for `ru.kryu.ferryfile` hit the
  release install instead.
- Git worktrees have no `local.properties`; run Gradle there with `ANDROID_HOME` set.

## Branches, commits and releases

- Every change lives on a `feature/<name>` branch cut from `develop`. Nothing is committed to
  `develop` or `main` directly.
- A finished feature is merged into `develop` and its branch is deleted.
- When a batch of changes has accumulated, `develop` is merged into `main` with a version bump
  and a new tag.
- A commit message is one short line (up to 80 characters), in the imperative — for example
  `Add Stopping server state`. No body, no trailers.

The exact procedure is in [docs/RELEASING.md](docs/RELEASING.md); version numbering is in
[docs/VERSIONING.md](docs/VERSIONING.md).
