# FerryFile

Transfer files between your Android phone and PC over local WiFi — no cables, no cloud, no external internet required.

## How it works

1. Open FerryFile on your Android device
2. Grant access to the folders you want to share
3. Tap **Start server** — the app displays an IP address and QR code
4. On your PC, open a browser and navigate to that address (e.g. `http://192.168.1.5:8080`)
5. Enter your password and start transferring files in both directions

Everything stays on your local network. Nothing leaves your WiFi.

## Features

- **Phone → PC:** select one or multiple files in the browser, download them as a ZIP
- **PC → Phone:** upload files from the browser directly into a chosen folder on the device
- **Real-time progress:** transfer progress updates live in the browser
- **Password protection:** access to the web interface is secured by a password you set
- **Background operation:** the server keeps running when the app is minimized
- **No special permissions:** folder access is granted explicitly by you through the system file picker

## Requirements

- Android 11 (API 30) or higher
- Both devices on the same WiFi network

## Tech stack

- Kotlin + Jetpack Compose
- Ktor embedded server (Netty)
- Storage Access Framework (SAF)

## License

[MIT](LICENSE)
