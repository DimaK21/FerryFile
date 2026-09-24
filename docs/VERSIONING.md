# Versioning

The Android version has two independent parts:

- `versionName` is the user-facing SemVer-style string: `major.minor.patch[-suffix]`.
- `versionCode` is the integer used by Google Play to order artifacts.

The source of truth is the root `gradle.properties` file:

```properties
APP_VERSION_MAJOR=0
APP_VERSION_MINOR=1
APP_VERSION_PATCH=0
APP_VERSION_SUFFIX=
APP_VERSION_CODE=1
```

This configuration produces `versionName = 0.1.0` and `versionCode = 1`.

## Changing A Version

- Increment `APP_VERSION_PATCH` for backward-compatible bug fixes.
- Increment `APP_VERSION_MINOR` for backward-compatible features and reset patch to `0`.
- Increment `APP_VERSION_MAJOR` for incompatible product changes and reset minor and patch to `0`.
- Set `APP_VERSION_SUFFIX` to a prerelease identifier such as `beta`, `rc.1`, or `dev`. Leave it empty for a stable release.
- Increase `APP_VERSION_CODE` for every upload to Google Play. Never reuse a code, even when only the suffix changes.

The branch, merge and tag flow for a release is described in [RELEASING.md](RELEASING.md).

Examples:

| Properties | Result |
| --- | --- |
| `0.1.0`, empty suffix, code `1` | `0.1.0`, `versionCode 1` |
| `0.1.0`, suffix `beta`, code `2` | `0.1.0-beta`, `versionCode 2` |
| `0.1.1`, suffix `beta`, code `3` | `0.1.1-beta`, `versionCode 3` |
| `0.1.1`, empty suffix, code `4` | `0.1.1`, `versionCode 4` |

## CI Overrides

Gradle properties can be overridden without changing the checked-in defaults:

```bash
./gradlew bundleRelease \
  -PAPP_VERSION_MAJOR=0 \
  -PAPP_VERSION_MINOR=1 \
  -PAPP_VERSION_PATCH=0 \
  -PAPP_VERSION_SUFFIX=beta \
  -PAPP_VERSION_CODE=2
```

The `versionCode` should be stored in the release system or another persistent source so that parallel builds cannot reuse it.
