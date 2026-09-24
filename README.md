# FerryFile

Transfer files between your Android phone and PC over local WiFi — no cables, no cloud, no external internet required.

## How it works

1. Open FerryFile on your Android device
2. Grant access to the folders you want to share
3. Tap **Start server** — the app shows the server address and a six-digit PIN
4. On your PC, open a browser and navigate to that address (e.g. `http://192.168.1.5:8080`)
5. Enter the PIN and start transferring files in both directions

Everything stays on your local network. Nothing leaves your WiFi.

HTTP is used by default. To use HTTPS, enable **Use HTTPS** in Settings.
In this mode the server uses a device-specific self-signed certificate. Your browser may show a
certificate warning on the first connection; compare its fingerprint with the one displayed in
the FerryFile app before continuing.

## Features

- **Phone → PC:** download a single file as is, or select several files and folders and get a ZIP
- **PC → Phone:** upload files from the browser directly into a chosen folder on the device
- **Real-time progress:** transfer progress updates live in the browser
- **Optional HTTPS:** enable HTTPS with a self-signed certificate when needed
- **PIN protection:** access to the web interface is secured by a temporary six-digit PIN
- **Background operation:** the server keeps running when the app is minimized
- **No special permissions:** folder access is granted explicitly by you through the system file picker

Known limitations are listed in [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md).

## Requirements

- Android 11 (API 30) or higher
- Both devices on the same WiFi network

## Tech stack

- Kotlin + Jetpack Compose
- Ktor embedded server (Netty with optional HTTPS)
- Storage Access Framework (SAF)

## Building

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the project layout, coding rules, and branch and
commit conventions. Release procedure: [docs/RELEASING.md](docs/RELEASING.md); version numbering:
[docs/VERSIONING.md](docs/VERSIONING.md).

## License

[MIT](LICENSE)
