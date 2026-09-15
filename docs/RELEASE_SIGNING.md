# Release signing

`release` is signed with a dedicated keystore. It never falls back to the
debug key.

For a new Google Play app, create an upload key. If the app already exists,
use the existing upload key instead of generating a new one:

```bash
mkdir -p app/release
keytool -genkeypair -v \
  -keystore app/release/ferryfile-upload.jks \
  -alias ferryfile-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and fill in the
same passwords and alias. Then build the signed bundle:

```bash
./gradlew :app:bundleRelease
```

The four `RELEASE_*` values can also be supplied as Gradle properties or CI
environment variables. The keystore path is relative to the repository root.
Release packaging fails if any value is missing or the keystore file does not
exist.
