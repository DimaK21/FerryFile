# Проверка на устройстве

Сквозная проверка ветки `develop` на физическом телефоне: OnePlus Nord N10 (BE2029),
Android 11, SDK 30. Клиентом выступал Mac в той же Wi-Fi-сети, как и реальный пользователь.
Два найденных отказа исправлены и перепроверены на том же устройстве; отчёт о них — ниже по файлу.

---

# FerryFile — Device Verification Report

**Date:** 2026-09-13
**Branch:** `develop`
**Device:** OnePlus Nord N10 (BE2029), Android 11 (SDK 30), adb serial `a3932ac0`, USB
**Phone Wi-Fi address:** `192.168.0.5` · **Mac address:** `192.168.0.4`
**Package:** `ru.kryu.ferryfile`

All screenshots referenced below are saved under `/tmp/ferryfile-verify/`. All curl commands were run from the Mac against `http://192.168.0.5:<port>`.

---

## Setup

### 1. Install current build, launch app — PASS

`./gradlew :app:installDebug --console=plain` completed successfully:

```
BUILD SUCCESSFUL in 12s
41 actionable tasks: 1 executed, 40 up-to-date
```

Launched via `monkey -p ru.kryu.ferryfile -c android.intent.category.LAUNCHER 1`. `mCurrentFocus` confirmed `ru.kryu.ferryfile/ru.kryu.ferryfile.MainActivity`. First screen: title "FerryFile", gear icon, a purple "Start Server" button, and a "● Stopped" status line. No crash, nothing visually broken.

### 2. Test file tree — PARTIAL / BLOCKED on the SAF picker, worked around

Created the required fixture tree under `/sdcard/Download/ferrytest/` via `adb shell`:
- `report.pdf` (27 bytes, plain content)
- `Отчёт.pdf` (30 bytes, Cyrillic name)
- `my report.txt` (27 bytes, space in name)
- `docs/a.txt` (13 bytes), `docs/sub/b.txt` (17 bytes)
- `x/dup.txt` (16 bytes, "dup content in x"), `y/dup.txt` (16 bytes, "dup content in y")

**Picker blocker (its own entry, see below) meant "Settings → Add Folder" could not be exercised.** Per the coordinator's ruling, I copied the entire fixture tree byte-for-byte into an already-granted folder instead: `cp -r /sdcard/Download/ferrytest/. /sdcard/DCIM/ferrytest/`. Verified with `find /sdcard/DCIM/ferrytest -type f`:

```
/sdcard/DCIM/ferrytest/docs/a.txt
/sdcard/DCIM/ferrytest/docs/sub/b.txt
/sdcard/DCIM/ferrytest/my report.txt
/sdcard/DCIM/ferrytest/report.pdf
/sdcard/DCIM/ferrytest/x/dup.txt
/sdcard/DCIM/ferrytest/y/dup.txt
/sdcard/DCIM/ferrytest/Отчёт.pdf
```
and `ls -laR` confirmed correct byte sizes on every file. `primary:DCIM` was already listed as a granted folder in Settings from an earlier session (alongside `Госуслуги` and `primary:Books`), so no new SAF grant was required to use it. The app saw the new files immediately (see step 6) — no server restart was needed; this was **not** a caching artefact, `DocumentFile` reads the tree live.

---

## Picker blocker (own entry, BLOCKED — not a product defect)

**What I tried:** Opened Settings → Add Folder, which correctly launches the stock Android SAF folder picker (`com.google.android.documentsui`, `PickActivity`). I then tried to tap into a folder (e.g. `Download`) to navigate down to `ferrytest` before selecting it, using every synthetic-input technique available:
- `adb shell input tap` at the exact coordinates of the folder row (verified via `uiautomator dump` bounds, in both grid and list view)
- `adb shell input swipe X Y X Y <duration>` (0-tap-equivalent)
- `adb shell input motionevent DOWN/UP` with explicit holds from ~100ms up to 700ms (to also rule out a long-press/CAB requirement)
- Two taps in quick succession (double-tap, 100–250ms apart)
- Tapping the folder icon, the label text, and the full row bounds separately
- Raw `sendevent` writes directly to `/dev/input/event2` (the `touchpanel` device, type-B multitouch protocol) to bypass Android's input-injection path entirely — **refused with `Permission denied`** by SELinux even though the node is world-writable (`crw-rw-rw-`); the device is not rooted, so there is no way around this.

**What worked, in the same screen, with the same tooling:** the hamburger menu (opens the roots drawer), the grid/list view toggle, scrolling/swiping the list, the "Новая папка" (New folder) link, and the "Использовать эту папку" (Use this folder) button — tapping the latter at the storage root correctly produced the real system toast "В целях защиты вашей конфиденциальности мы заблокировали доступ к этой папке" (a legitimate Android 11 scoped-storage restriction on granting the bare root). So general touch delivery into this activity's content area is not broken; only "tap a folder row to enter it" (or open a file) never fires — I also tried tapping a plain PNG file row and nothing opened.

**Conclusion:** the "Add Folder" flow itself went **unexercised** this session. This is stock Android/DocumentsUI UI that the plan under test never touched, so it does not reflect on the app. Marked **BLOCKED**, not FAIL.

---

## The checks

### 3. Settings screen back affordance — PASS

Screenshot: `/tmp/ferryfile-verify/03-settings-backarrow.png`

The Settings top app bar shows a normal Material back **arrow icon** (content-desc `"Back"`, confirmed via `uiautomator` dump: `<View desc="Back">`), not the text "← Back". Tapping it, and separately the system back gesture (`adb shell input keyevent KEYCODE_BACK`), both returned correctly to the Home screen.

### 4. Home screen with server started — PASS

Screenshot: `/tmp/ferryfile-verify/04-home-running.png`

Large readable address `http://192.168.0.5:8080` and a separate large **PIN: 481614**. No QR code anywhere on the screen.

### 5. Login endpoint — PASS

```
$ curl -s -i -X POST http://192.168.0.5:8080/login -H "Content-Type: application/json" -d '{"pin":"000000"}'
HTTP/1.1 401 Unauthorized
Content-Length: 11
Content-Type: text/plain; charset=UTF-8

Invalid PIN
```

```
$ curl -s -i -X POST http://192.168.0.5:8080/login -H "Content-Type: application/json" -d '{"pin":"481614"}' -c cookies/jar.txt
HTTP/1.1 200 OK
Set-Cookie: FERRYFILE_SESSION=%7B%22token%22%3A%228c650fba-0d47-4b7f-b6fe-b0604a43e461%22%7D; Max-Age=604800; Expires=Sun, 20 Sep 2026 11:08:26 GMT; Path=/; HttpOnly
Content-Length: 0
```
(Note: the real endpoint is `/login`, not `/api/login` — the latter 404s.)

### 6. List root — PASS

```
$ curl -s "http://192.168.0.5:8080/api/list?path=/" -b cookies/jar.txt
{"path":"/","items":[
  {"name":"Госуслуги","size":0,...,"isDirectory":true,"path":"0"},
  {"name":"DCIM","size":0,...,"isDirectory":true,"path":"1"},
  {"name":"Books","size":0,...,"isDirectory":true,"path":"2"}
]}
```

Listing `path=1` (DCIM) immediately showed `ferrytest` (freshly copied moments earlier), and listing into it showed all 6 fixture entries with correct sizes — live `DocumentFile` read, no restart needed.

### 7. Download a single plain file — PASS

```
$ curl -s -G "http://192.168.0.5:8080/api/download" --data-urlencode "path=1/ferrytest/report.pdf" -b cookies/jar.txt -o downloaded-report.pdf -D report-headers.txt
HTTP/1.1 200 OK
Content-Disposition: attachment; filename="report.pdf"; filename*=UTF-8''report.pdf
Content-Length: 27
Content-Type: application/pdf
```
No `Transfer-Encoding` header present. `diff` between the downloaded file and the on-device original: **identical**, 27 bytes both sides.

### 8. Cyrillic filename and filename-with-space downloads — PASS

Cyrillic (`Отчёт.pdf`):
```
HTTP/1.1 200 OK
Content-Disposition: attachment; filename="_____.pdf"; filename*=UTF-8''%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf
Content-Length: 30
Content-Type: application/pdf
```
`%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf` percent-decodes (UTF-8) to exactly `Отчёт.pdf`. ASCII fallback `filename="_____.pdf"` (5 underscores for the 5 Cyrillic characters) is present too.

Space (`my report.txt`):
```
HTTP/1.1 200 OK
Content-Disposition: attachment; filename="my report.txt"; filename*=UTF-8''my%20report.txt
Content-Length: 27
Content-Type: text/plain
```
Space encodes as `%20`, not `+`.

### 9. Download a folder — PASS

```
$ curl -s -G "http://192.168.0.5:8080/api/download" --data-urlencode "path=1/ferrytest/docs" -b cookies/jar.txt -o docs.zip -D docs-headers.txt
HTTP/1.1 200 OK
Content-Disposition: attachment; filename="docs.zip"; filename*=UTF-8''docs.zip
Transfer-Encoding: chunked
Content-Type: application/zip
```
`unzip -l docs.zip`:
```
       17  ...   docs/sub/b.txt
       13  ...   docs/a.txt
```
Extracted and verified content: `docs/a.txt` = "nested a file", `docs/sub/b.txt` = "nested sub b file". Nesting preserved correctly.

### 10. Download a multi-selection, including duplicate names — PASS

Two different files:
```
$ curl -s -G "http://192.168.0.5:8080/api/download" --data-urlencode "path=1/ferrytest/report.pdf" --data-urlencode "path=1/ferrytest/docs" -b cookies/jar.txt -o selection1.zip
HTTP/1.1 200 OK
Content-Disposition: attachment; filename="ferryfile-selection.zip"; filename*=UTF-8''ferryfile-selection.zip
```
Contains `report.pdf`, `docs/sub/b.txt`, `docs/a.txt` — all 3 entries present.

Duplicate-named files (`x/dup.txt` + `y/dup.txt`):
```
HTTP/1.1 200 OK
Content-Disposition: attachment; filename="ferryfile-selection.zip"
```
`unzip -l`:
```
       16  ...   dup.txt
       16  ...   dup (2).txt
```
Extracted: `dup.txt` = "dup content in x", `dup (2).txt` = "dup content in y". **Both files survive under distinct names; archive is not corrupted.**

### 11. Upload a file into a folder — PASS (headline bug fixed)

```
$ curl -s -X POST "http://192.168.0.5:8080/api/upload?path=1%2Fferrytest&transferId=test-1" -b cookies/jar.txt -F "file=@upload-test.txt"
HTTP/1.1 200 OK
Content-Type: application/json

{"files":1,"bytes":54}
```
Confirmed on device: `/sdcard/DCIM/ferrytest/upload-test.txt`, 54 bytes, content byte-identical to the source file (`Upload test file content Sun Sep 13 16:09:59 +05 2026`).

### 12. Upload into the virtual root — PASS

```
$ curl -s -X POST "http://192.168.0.5:8080/api/upload?path=%2F&transferId=test-root" -b cookies/jar.txt -F "file=@root-upload-attempt.txt"
HTTP/1.1 400 Bad Request
Content-Type: application/json

{"error":"root_not_writable"}
```
Confirmed nothing was written anywhere on `/sdcard` matching that filename.

### 13. Two transfers at once — PASS

Two concurrent downloads (docs.zip + report.pdf) both returned HTTP 200, no 409. A concurrent download + upload (my report.txt download + concurrent-upload.txt upload) also both returned HTTP 200 — `{"files":1,"bytes":28}` for the upload — no 409 anywhere, no serialization stall observed for these smaller payloads.

### 14. Upload a larger file — **FAIL** (reproducible stall, no error surfaced to the client)

Created `/tmp/ferryfile-verify/bigfile.bin`, `dd if=/dev/urandom bs=1m count=75`, exact size **78,643,200 bytes**, SHA-256 `57c646edf0bb188bb8012116a664a4dd7511186748029deb9d5f3295a193ab82`.

**Attempt 1** (backgrounded, no timeout set): the `curl` process was still alive with near-zero CPU time 4+ minutes after starting. Device-side file `/sdcard/DCIM/ferrytest/bigfile.bin` was sampled twice, 5 seconds apart, and was identical both times at **51,392,037 bytes** — a hard stall, not merely slow. I killed the stuck `curl` process.

**Attempt 2** (foreground, fresh `bigfile.bin`, `--max-time 180`, targeting the same DCIM folder, `transferId=test-bigfile-retry`):
```
$ curl -sS --max-time 180 -X POST "http://192.168.0.5:8080/api/upload?path=1%2Fferrytest&transferId=test-bigfile-retry" -b cookies/jar.txt -F "file=@bigfile.bin" -w "HTTPSTATUS:%{http_code} TIME:%{time_total}s SIZE_UPLOAD:%{size_upload}\n"
curl: (28) Operation timed out after 180001 milliseconds with 0 bytes received
HTTPSTATUS:100 TIME:180.001871s SIZE_UPLOAD:59516288
```
Only an `HTTP/1.1 100 Continue` header was ever received — no final status, no JSON body, before curl gave up. The client believed it had pushed 59,516,288 bytes onto the socket, but the file on disk had again stopped growing at exactly **51,392,037 bytes** — the identical figure as Attempt 1.

**Attempt 3** (foreground, same file, uploaded into `Books` instead — a plain SAF-granted folder, not the MediaStore-backed `DCIM` — `transferId=test-bigfile-books`, ruling out a DCIM/MediaStore-specific cause):
```
curl: (28) Operation timed out after 180002 milliseconds with 0 bytes received
HTTPSTATUS:100 TIME:180.003036s SIZE_UPLOAD:60592680
```
Device-side `/sdcard/Books/bigfile.bin`: again **51,392,037 bytes**, byte-for-byte the same stopping point as the two DCIM attempts, in a completely different destination folder.

**Diagnosis (observed, not fixed):** the exact same byte count recurring across three independent attempts, in two different target folders, while the TCP layer kept accepting more bytes than the app ever wrote to disk, indicates a deterministic backpressure/deadlock in the upload write path rather than random network slowness — a real stall, not "just slow." The server process itself did **not** crash or become unresponsive: `GET /api/list` continued returning `200` normally while the stuck upload sat open. `adb logcat --pid=<ferryfile pid>` around both stalls showed no exception, no `FATAL`, nothing from the app at all during the stall window — it fails silently. The orphaned 51,392,037-byte partial file is left behind on disk with no indication to the client that the transfer failed (the client only learns anything from its own `--max-time` abort).

I did not attempt to fix or diagnose the cause in source further (out of scope for this verification task) — see `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt` `post("/api/upload")` for the code path if this needs to be reproduced.

Cleaned up both partial 51 MB artefacts from the device afterward (`/sdcard/DCIM/ferrytest/bigfile.bin`, `/sdcard/Books/bigfile.bin`) — they were not part of the intended fixture tree.

### 15. Stop action in the notification — PARTIAL (server stop PASS, Home-screen state refresh FAIL)

Screenshots: `/tmp/ferryfile-verify/15-notification-shade.png` (collapsed), `15b-notification-expanded.png`, `15c-notification-expanded-stop.png` (STOP button visible), `15d-after-stop.png`, `15e-after-relaunch.png`.

Collapsed and expanded notification both show only:
```
FerryFile
http://192.168.0.5:8080
[STOP]
```
No PIN anywhere in the notification. Confirmed independently via `dumpsys notification --noredact`: `android.text=String (http://192.168.0.5:8080)`, no PIN in any extra.

Tapped STOP (`uiautomator`-derived bounds `[35,1735][201,1870]`). Result:
- **Server actually stopped** — `curl --max-time 5 http://192.168.0.5:8080/api/list...` → connection refused (curl exit 7). PASS.
- **Home screen did NOT show Stopped** — the already-open Home screen (`MainActivity`, never backgrounded/recreated) kept showing "● Running" and the old PIN even several seconds after the server was confirmed dead over the network (`15d-after-stop.png`). This is a genuine state-observation gap: force-stopping and relaunching the app (`15e-after-relaunch.png`) immediately shows the correct "● Stopped" state, so the underlying stored state is correct — the already-open `HomeViewModel`/UI just never re-collects it when the server is stopped from outside the app (via the notification action) while the screen is on. **FAIL** against the checklist's explicit expectation ("the Home screen should show Stopped").

### 16. PIN rotation — PASS

Restarted the server from the Home screen. New PIN: **485357** (old PIN was 481614 — different). Screenshot: `/tmp/ferryfile-verify/16-new-pin.png`.

```
$ curl -s -i -X POST http://192.168.0.5:8080/login -d '{"pin":"481614"}'
HTTP/1.1 401 Unauthorized
Invalid PIN

$ curl -s -i -X POST http://192.168.0.5:8080/login -d '{"pin":"485357"}'
HTTP/1.1 200 OK
Set-Cookie: FERRYFILE_SESSION=...
```
Old PIN correctly rejected; new PIN accepted.

### 17. Web UI in a browser — BLOCKED (no GUI display in this environment)

`open -a "Google Chrome" http://192.168.0.5:8080` was issued, and the login page confirmed reachable and well-formed via `curl http://192.168.0.5:8080/login` (title "FerryFile — Login", numeric-PIN input field with `maxlength="6"`, "Unlock" button, client-side JS that posts to `/login`). However, `screencapture` failed with `could not create image from display` — this agent's Mac session has no attached display/window server, so a screenshot of the actual rendered browser window could not be captured. Not attempted further per "do not spend long."

---

## Summary table

| # | Check | Result |
|---|-------|--------|
| 1 | Install + launch, no crash | PASS |
| 2 | Test file tree created & shared | PARTIAL — tree created; sharing done via existing grant (DCIM), not the picker (see blocker) |
| — | SAF "Add Folder" picker automation | **BLOCKED** — folder-row taps ignored by synthetic input; not a product defect |
| 3 | Settings back = Material arrow, not text; back gesture works | PASS |
| 4 | Home shows address + PIN, no QR | PASS |
| 5 | Login: wrong PIN 401, correct PIN 200 + cookie | PASS |
| 6 | List root | PASS |
| 7 | Download single plain file (raw, headers, byte-identical) | PASS |
| 8 | Cyrillic + space filename headers/encoding | PASS |
| 9 | Download folder as zip, nesting preserved | PASS |
| 10 | Multi-selection zip + duplicate-name survival | PASS |
| 11 | Upload into a folder (headline bug) | PASS |
| 12 | Upload into virtual root rejected | PASS |
| 13 | Two concurrent transfers, no 409 | PASS |
| 14 | Large (75 MB) upload completes, bytes match | **FAIL** — reproducible silent stall at 51,392,037 bytes, 3/3 attempts, no error to client |
| 15a | Notification shows address, not PIN | PASS |
| 15b | Stop actually stops the server | PASS |
| 15c | Home screen shows Stopped after notification Stop | **FAIL** — stale UI state until app relaunch |
| 16 | PIN rotates on restart, old PIN rejected | PASS |
| 17 | Browser screenshot of login page | BLOCKED — no display in this environment; page content verified via curl instead |

**Tally: 13 PASS, 2 FAIL, 2 BLOCKED, 1 PARTIAL** (item 2 folded into the picker blocker).

---

## Anything else noticed, not on the checklist

- The app's existing Settings screen already listed three previously-granted folders from earlier sessions (`Госуслуги`, `primary:DCIM`, `primary:Books`) — these predate this run and were left untouched.
- `POST /api/login` does not exist; the real path is `POST /login` (worth knowing for anyone else writing curl scripts against this server — not a defect, just a note for the record since the brief's example paths assumed `/api/login`).
- Free space on `/sdcard` was never a factor: `df /sdcard` showed 44.6 GB available throughout, ruling out storage exhaustion as the cause of the step 14 stall.
- The device has AnyDesk remote-control and a call-recorder app with active `AccessibilityService`s registered (`com.anydesk.adcontrol.ad1/...AccService`, `com.catalinagroup.callrecorder...`). `touchExplorationEnabled=false` was confirmed via `dumpsys accessibility`, so these were ruled out as the cause of the picker blocker, but they're worth knowing about if this device is reused for future automated runs.

---

## Cleanup performed

- Removed the two orphaned ~51 MB partial-upload artefacts (`/sdcard/DCIM/ferrytest/bigfile.bin`, `/sdcard/Books/bigfile.bin`) left behind by the step 14 stalls.
- Left the full fixture tree in place under `/sdcard/DCIM/ferrytest/` (including the `upload-test.txt` and `concurrent-upload.txt` files created during steps 11/13) and the original copy under `/sdcard/Download/ferrytest/`, per instructions.
- Server left in the **Stopped** state on the device at the end of the run.

---

# FerryFile — Device Fixes Report

**Date:** 2026-09-13
**Branch:** `develop`
**Device:** OnePlus Nord N10 (BE2029), Android 11 (SDK 30), adb serial `a3932ac0`, USB
**Phone Wi-Fi address:** `192.168.0.5` · **Mac address:** `192.168.0.4`
**Package:** `ru.kryu.ferryfile`

This fixes the two defects found in `.superpowers/sdd/2026-09-12-ferryfile-fixes/device-verification-report.md`:
checklist item 14 (large upload stalls silently) and item 15c (Home screen shows stale state after the
notification's Stop action).

---

## Defect 1 — large upload stalled at 51,392,037 bytes

### Root cause, with evidence

Ktor 3.1.3's `ApplicationCall.receiveMultipart(formFieldLimit: Long = -1L)` applies a byte ceiling to
**every multipart part body**, not just text form fields, despite the parameter's name. Confirmed by
reading the actual library sources (sources jars in the Gradle cache, not assumed):

- `ktor-server-core-jvm-3.1.3-sources.jar` → `io/ktor/server/request/ApplicationReceiveFunctionsJvm.kt`:
  ```kotlin
  internal actual val DEFAULT_FORM_FIELD_LIMIT: Long
      get() = System.getProperty("io.ktor.server.request.formFieldLimit")?.toLongOrNull() ?: (50 * 1024 * 1024L)
  ```
  i.e. the default is exactly **52,428,800 bytes (50 MiB)** when the app never overrides it — and
  `FileRoutes.kt` was calling the zero-arg `call.receiveMultipart()`, so it never did.

- `ktor-http-cio-jvm-3.1.3-sources.jar` → `io/ktor/http/cio/CIOMultipartDataBase.kt` passes this same
  value into `parseMultipart(...)` as `maxPartSize`, and `Multipart.kt`'s `parseMultipart` coroutine calls
  `parsePartBodyImpl(boundaryPrefixed, countedInput, body, headersMap, maxPartSize)` for **every** part —
  `PartData.FileItem` parts included, there is no special-casing by content-disposition/filename before
  the limit is enforced.

- `ktor-io-jvm-3.1.3-sources.jar` → `io/ktor/utils/io/ByteReadChannelOperations.kt`'s `readUntil(...)` /
  `ByteChannelScanner` throws `IOException("Limit of $limit bytes exceeded ...")` once the running byte
  count for that part crosses the limit — it does not silently truncate, it throws.

**Confirmed the throw is what fired**, not a guess: I temporarily reverted the fix (restored the bare
`call.receiveMultipart()`) and re-ran the new regression test (see below) — it failed with:
```
FileRoutesTest > upload larger than Ktor's default multipart part limit still completes FAILED
    java.io.IOException at Multipart.kt:412
```
line 412 of `Multipart.kt` is exactly `throwLimitExceeded(...)` in the library source read above. I then
restored the fix and the test passed again.

**Why nothing reached the client and nothing reached logcat (as asked to explain):** the multipart parser
runs as a child coroutine of the same call context; when it throws past the limit, the file part's
underlying channel is closed with that exception. The upload route's own read of that channel then throws
too, is caught by the route's existing `catch (e: Exception)`, and the route tried `call.respond(...)`. But
the client (curl on the real device test) was still mid-stream pushing the rest of a 75 MiB body into a
socket the server had stopped reading from — once the OS-level receive buffer filled, the client's writes
blocked and the connection wedged before any response bytes could go out, so the client saw nothing but its
own eventual `--max-time` timeout. Separately, the existing catch block never called any logger at all
(only `transferProgress.emitError`, which no listener was subscribed to in the curl-only repro), which is
why nothing appeared in `adb logcat` either.

### Fix

`app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt`:

1. **Root cause fix** — the route now calls
   `call.receiveMultipart(formFieldLimit = NO_PRACTICAL_MULTIPART_PART_LIMIT)` where
   `NO_PRACTICAL_MULTIPART_PART_LIMIT = Long.MAX_VALUE`. This is not "a bigger constant that moves the
   cliff further out" — it expresses "no practical cap" directly, per the brief. A large doc comment on the
   constant explains why this is safe here: `SaveUploadUseCase`/`saveUpload` streams each file part
   straight to disk without ever buffering it in memory, so there's no memory-safety reason to bound a file
   part's size. It also notes the one caveat honestly: a non-file multipart *field* is still fully buffered
   in memory by Ktor before this route ever sees it, but `/api/upload` is behind session auth and the only
   real client (FerryFile's own web UI) never sends one, so this doesn't newly expose anything.

2. **Observability / failure path** — the `catch (e: Exception)` block now:
   - Logs via `android.util.Log.e(...)` (not Ktor's `call.application.log`, which is a silent no-op here
     since this app ships no SLF4J binding — confirmed there is no `slf4j`/`logback` dependency anywhere in
     the Gradle files, so Ktor's own logger was *always* going to be invisible in logcat regardless of the
     multipart bug). This directly fixes "nothing appears in logcat" for any future upload failure, not
     just this one.
   - Drains any remaining multipart parts (`multipart?.readPart()` in a loop, wrapped in `runCatching`)
     before attempting to respond, so a client still mid-stream isn't left backed up against a socket the
     server stopped reading — this is the generic fix for "the client must not be left hanging" for *any*
     cause of abort, not only the formFieldLimit one.
   - Wraps the `call.respond(...)` itself in `runCatching` and logs if even that fails, so a failure to
     notify the client is itself diagnosable instead of silently swallowed.
   - Keeps the existing `TransferEvent.Error` emission unchanged, as required.

### Regression test

`app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt` — new test
`upload larger than Ktor's default multipart part limit still completes`:
- Uploads a `52_428_800 + 2_000_000`-byte (~54.4 MiB) in-memory file through the same `testApplication` +
  `FakeFileStorageRepository` harness the other upload tests use — a few MiB past the real 50 MiB boundary
  that broke, not an arbitrary large number.
- Asserts `HTTP 200`, the exact JSON body `{"files":1,"bytes":<size>}`, and that the bytes captured by the
  fake storage's `ByteArrayOutputStream` are byte-identical (`assertArrayEquals`) to what was sent — so it
  cannot pass merely because the server accepted the connection.
- **Verified this test fails for the right reason**: temporarily reverting only the `formFieldLimit`
  override reproduces the exact library-level `IOException` at `Multipart.kt:412` described above; with the
  fix restored it passes in ~0.19s (see `app/build/test-results/testDebugUnitTest/TEST-...FileRoutesTest.xml`).
- Full suite: 112 tests, 0 failures (see Test results below).

### On-device re-verification

Built a fresh 75 MiB (`78,643,200`-byte) file with `dd if=/dev/urandom bs=1m count=75`, SHA-256
`4e7a8fbc5f6f98365f4b57e0c66f9139eee7e2ddbe19ba624b1f5f883587deb5`. Installed the fixed debug build, started
the server from the app (PIN `986174`), logged in, and uploaded into the same `DCIM/ferrytest` folder used
by the original repro.

**Primary result (representative of the real web client)** — uploaded with Python `requests`, which (like a
browser's `fetch`/`XHR`, and unlike curl) does not send `Expect: 100-continue` for a large body:
```
status: 200
body: {"files":1,"bytes":78643200}
elapsed: 7.44s
```
On-device: `sha256sum /sdcard/DCIM/ferrytest/bigfile-browserlike.bin` →
`4e7a8fbc5f6f98365f4b57e0c66f9139eee7e2ddbe19ba624b1f5f883587deb5` — **identical to the source**. Full
78,643,200 bytes landed, checksum matches, in 7.44 seconds. No stall, no truncation at 51,392,037 bytes.

**Additional confirmation with curl** (4 separate attempts, including a repeat of the exact original repro
command): every attempt wrote the **complete** 78,643,200-byte file to disk with a matching SHA-256,
including one uploaded into `Books` (a non-MediaStore folder, matching the original report's third repro).
The stall is gone; the server no longer stops writing early.

**A second, pre-existing issue this uncovered (see "Anything still wrong" below):** curl automatically adds
`Expect: 100-continue` for bodies above roughly 1 MiB. When it does, the *bytes on disk are still complete
and correct* (verified above), but curl itself fails to parse the final response with `curl: (8) Header
without colon`. This is a genuine bug inside Ktor 3.1.3's own `ktor-server-cio` engine (not our code, not
new from this fix) — see that section for the source-level evidence. It was previously invisible only
because the formFieldLimit stall meant such requests never got far enough to reach a final response at all.

---

## Defect 2 — Home screen showed stale "Running" state after Stop from the notification

### Confirmed cause

`HomeScreen.kt` called `viewModel.refresh()` from `LaunchedEffect(Unit)`, which runs exactly once per
composition. When the server is stopped from the notification's Stop action while `MainActivity` is merely
paused (not recreated), nothing re-triggers that effect, so the screen never re-reads state. Confirmed
`ServerRepositoryImpl.refresh()` is already correctly self-healing —
```kotlin
private suspend fun refreshLocked() {
    _state.value = if (!server.isRunning) {
        accessCodes.revoke()
        ServerState.Stopped
    } else { ... }
}
```
— it just was never being called on resume.

### Fix

`app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt`: replaced
`LaunchedEffect(Unit) { viewModel.refresh() }` with
`LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }` from
`androidx.lifecycle.compose` (already resolved on the classpath transitively via
`lifecycle-viewmodel-compose:2.8.7`, confirmed present in the Gradle cache — no new dependency added). This
re-reads server state every time the screen comes back to the foreground, including the very first
appearance (`ON_RESUME` fires on initial display too, so the old first-load behavior is preserved), and
specifically covers the notification-Stop-while-paused case the old code missed. Because
`refreshServerState()` revokes the PIN and republishes `Stopped` whenever the engine isn't actually running,
this eliminates the window where a live-looking PIN the server would reject stays on screen, rather than
merely shrinking it.

### On-device re-verification

1. Started the server from the Home screen (PIN `986174`, confirmed visible on screen).
2. Pressed **Home** to background the app (paused, not killed/relaunched).
3. Opened the notification shade, expanded the FerryFile notification, tapped **STOP**.
4. Confirmed the server was actually down over the network: `curl --max-time 5 http://192.168.0.5:8080/api/list?path=/` → connection refused (exit 7); a login attempt with the old PIN `986174` also connection-refused.
5. Returned to the app via the Recents/task-switcher (not a fresh launch — same task, same `MainActivity` instance) and screenshotted immediately.

Result: **Home screen showed "● Stopped" and the "Start Server" button immediately**, with no PIN or
address displayed, with no relaunch. Screenshot saved at
`.superpowers/sdd/2026-09-12-ferryfile-fixes/evidence/defect2-home-stopped-after-notification-stop.png`.

---

## Files changed

- `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt` — multipart limit fix, failure-path draining/logging/response-guarding.
- `app/src/main/java/ru/kryu/ferryfile/ui/home/HomeScreen.kt` — `LifecycleEventEffect(ON_RESUME)` instead of `LaunchedEffect(Unit)`.
- `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt` — new large-upload regression test + a small multipart-bytes test helper.

No `domain` files were touched (checked: no new `android.`/`androidx.`/`io.ktor.`/`dagger.hilt.android.`/`com.google.zxing.` imports were added anywhere in `domain`). No new Gradle dependencies were added.

## Test and build results

- `./gradlew :app:testDebugUnitTest --console=plain` — **BUILD SUCCESSFUL**, 112 tests, 0 failures, 0 errors.
- `./gradlew :app:assembleDebug --console=plain` — **BUILD SUCCESSFUL**.
- Both re-run together (`testDebugUnitTest assembleDebug`) immediately before committing — both green.

## Anything still wrong

**A pre-existing Ktor 3.1.3 `ktor-server-cio` bug, unrelated to our code, surfaced once defect 1 stopped
masking it:** when a client sends `Expect: 100-continue` (curl does this automatically for bodies above
~1 MiB; browsers' `fetch`/`XHR` do not), the engine's interim-response handling in
`CIOApplicationEngine.kt`:
```kotlin
val continueResponse = "HTTP/1.1 100 Continue\r\n"
...
output.writeStringUtf8(continueResponse)
```
writes only a single `\r\n` after the `100 Continue` status line. Per HTTP, an interim response's (empty)
header block must be terminated by a **blank line** (a second `\r\n`). `CIOApplicationResponse.sendResponseMessage()`
then writes the real final response (status line + headers + blank line) directly after, with no leading
separator of its own. The two concatenate into one malformed stream, e.g.:
```
HTTP/1.1 100 Continue\r\nHTTP/1.1 200 OK\r\nContent-Length: 22\r\n...
```
A strict client (curl) reading this sees `HTTP/1.1 200 OK` as an unterminated *header line* of the still-open
100-Continue response, which has no colon — hence curl's `curl: (8) Header without colon`.

This is **not our defect and not something this task's scope permits fixing** (it's inside the vendored
`ktor-server-cio` library, not app code; fixing it would mean patching or upgrading a dependency, both out
of scope here). It does **not** affect the deliverable for defect 1: verified with a client that doesn't
send `Expect: 100-continue` (Python `requests`, representative of the real browser-based web UI) that the
upload completes cleanly with a proper `200` and correct JSON body, and independently verified on-device
that the file that lands on disk is byte-for-byte and checksum-identical to the source in every case,
curl-triggered or not — the bytes written to storage were never wrong, only curl's own response parsing is
affected. Flagging this clearly rather than silently working around it or claiming it doesn't exist.

No other regressions or open issues found. The `bigfile*.bin` test artifacts and Python's `bigfile-browserlike.bin` were removed from `/sdcard/DCIM/ferrytest/` after verification; the server was left in the Stopped state on the device.

---

# Fix report — round 2 (commit c719271)

Review of commit `cf0a888` found the round-1 fix incomplete: one Critical (the drain could
never actually drain in the one case it existed for), three Important correctness/safety
gaps, a missing test category, and a Home-screen gap round 1 did not actually close (the
service bypassed the repository entirely on Stop, so nothing republished state regardless of
the ON_RESUME fix, whenever the activity never paused — e.g. a shade tap over an
already-visible Home screen). All six are fixed in `c719271`.

## CRITICAL — the drain read from the wrong channel

**Finding:** `FileRoutes.kt`'s catch block drained via `multipart.readPart()`. When the
*parser itself* is what failed (the only case the drain's own comment described), Ktor's
`CIOMultipartDataBase.readPart()` rethrows that exact cause on the very next call — its
`events` channel was closed with it, and `readPart()` only treats a plain
`ClosedReceiveChannelException` as "no more parts" (confirmed by reading
`CIOMultipartDataBase.kt:38-56` in the ktor-http-cio 3.1.3 sources: `readPartSuspend()`'s
`catch` clause names only `ClosedReceiveChannelException`). So the drain's own `runCatching`
silently ate that rethrow and consumed zero bytes — the exact deadlock it was meant to
prevent was unchanged.

**Fix:** drain the *raw* request channel instead — `call.request.receiveChannel().discard()`,
wrapped in `withTimeoutOrNull(DRAIN_TIMEOUT_MS)` (15s). Confirmed via
`BaseApplicationRequest.receiveChannel()` (ktor-server-core 3.1.3 sources) that this accessor
has no "already consumed" guard (unlike `ApplicationCall.receive()`/`receiveChannel()`, which
do), so it stays live and fed by the engine regardless of what `receiveMultipart()` did.

**A second problem this surfaced while testing:** even with the raw-channel drain, the new
"bogus declared length" test (below) still failed — not with a clean 500, but with the raw
`IOException` escaping the whole request, past the route's own `catch (e: Exception)` block,
all the way to the test client. Diagnosis: `receiveMultipart()`'s CIO parser runs as a child
coroutine parented on the *ambient* `coroutineContext` at the point it's called
(`DefaultTransformJvm.multiPartData` uses `PipelineContext.coroutineContext`), i.e. a direct
child of the route handler's own job. A parser failure therefore cancels the handler's job via
ordinary structured-concurrency child-failure propagation — independently of, and regardless
of, the route's own `try/catch` locally handling the same exception. The cancelled job then
re-throws at the handler's very next suspension point, so the carefully-constructed 500
response was being built and then discarded. **Fix:** wrapped the multipart-consuming section
in `supervisorScope { ... }`, which isolates the parser's failure from the handler's own job
— the exception still reaches the route exactly the same way (channel closed with cause,
`readPart()` throws it normally) but no longer cancels the surrounding job as a side effect.
Verified empirically: before the `supervisorScope` fix, the malformed-body test failed with an
escaped `IOException` from deep inside Ktor's coroutine machinery; after, it passes with a
clean `500 {"error":"upload_failed"}`.

## IMPORTANT 1 — `Long.MAX_VALUE` left non-file parts unbounded in memory

**Finding:** `CIOMultipartDataBase.partToData` (ktor-http-cio 3.1.3) only hands a part to the
caller as a stream when it has a filename; a part *without* one is read fully into memory via
`body.readRemaining()`, then again as a `String`, before the route ever sees it. Raising the
limit to `Long.MAX_VALUE` removed any bound on that in-memory read — an authenticated peer on
the LAN could OOM-kill the foreground service with one giant non-file part, which is a worse
hole than the original 50 MiB default.

**Fix:** bound `formFieldLimit` by the request's own declared `Content-Length` (already
captured as `totalBytes`): `totalBytes.takeIf { it > 0 } ?: FALLBACK_MULTIPART_PART_LIMIT_BYTES`.
A declared-75-MiB request is allowed its full 75 MiB (a no-op ceiling for the file part, which
streams straight to disk regardless), while an absent or unparseable `Content-Length` falls
back to a modest, named 8 MiB constant instead of being trusted at all. Doc comment on
`FALLBACK_MULTIPART_PART_LIMIT_BYTES` states this reasoning plainly, replacing the previous
(now-incorrect) "no practical cap" claim.

## IMPORTANT 2 — `CancellationException` swallowed by `runCatching`

**Finding:** both `runCatching` blocks in the catch clause (drain, then respond) caught
`Throwable`, silently absorbing a `CancellationException` that arrived during either — directly
undoing the `catch (e: CancellationException) { throw e }` rethrow one level up.

**Fix:** replaced both `runCatching` blocks with explicit `try/catch (CancellationException) { throw }`
/ `catch (Exception) { Log.e(...) }` pairs.

## IMPORTANT 3 — no failure-path test existed

Two new tests added to `FileRoutesTest.kt`:

- **`upload failure responds with an error and emits a TransferEvent Error`** — configures
  `FakeFileStorageRepository.createFileFails = true` (so `SaveUploadUseCase` returns
  `Result.Failed`), asserts `500` + `{"error":"upload_failed"}`, and asserts a
  `TransferEvent.Error(transferId = "tx-fail", code = "upload_failed", ...)` is emitted on
  `TransferProgress.events`.
- **`a part's bogus declared length ends the request in a response, not a hang`** — a
  hand-built multipart body whose one part declares `Content-Length: 999999999`, wildly
  exceeding the request's own (small) declared size, which is now this route's
  `formFieldLimit`. This makes the CIO parser throw synchronously inside its own coroutine —
  precisely the shape of failure the drain and `supervisorScope` fixes above exist for.
  Wrapped in `withTimeout(5_000)` so a regression (a hang) fails the test instead of hanging
  the suite. **This is the test that caught the job-cancellation bug**: before either fix it
  failed with an escaped `IOException`; after the drain fix alone it still failed exactly the
  same way; only after adding `supervisorScope` did it pass. It does *not*, on its own, prove
  the drain-target fix (reading the raw channel instead of the dead multipart parser): the
  test body here is ~230 bytes, so the backed-up-socket condition the drain exists for is
  structurally absent on the in-process test engine — this same test would have passed with
  the old, broken (`multipart.readPart()`-based) drain in place too, since there is nothing of
  consequence left to drain either way. That fix rests on the source-level analysis of
  `CIOMultipartDataBase.readPart()` (above) and the on-device re-verification of a real 75 MiB
  upload actually completing, not on this suite.

## MINOR — the large-upload test exercised the wrong branch

**Finding:** the original test used `formData { append("file", byteArray, headers) }`, which
(confirmed by reading `formDsl.kt` in ktor-client-core 3.1.3) auto-attaches a per-part
`Content-Length` header — steering the server into `parsePartBodyImpl`'s declared-length
`copyTo` fast path, never the `readUntil` streaming path that actually produced the silent
stall.

**Considered and rejected the literally-requested fix:** sending the part via
`ChannelProvider(size = null)` avoids a per-part `Content-Length`, but tracing
`MultiPartFormDataContent`'s `init` block (ktor-client-core `FormDataContent.kt`) shows the
*overall* request `Content-Length` is computed by summing each part's own size — a `null`
part size makes the whole body's length `null` too, so the request would be sent with no
`Content-Length` at all. That would defeat IMPORTANT 1's fix in the same commit (no declared
length → falls back to the 8 MiB constant → the 54 MiB test body would fail for an unrelated
reason), which is not what this test is supposed to prove.

**Fix implemented instead:** `rawMultipartFileBody(...)`, a small helper that builds the
multipart payload by hand as a `ByteArray` with no per-part `Content-Length` line, sent via
plain `setBody(ByteArray)` (which always declares its own exact size as the *overall* request
`Content-Length`). This is what curl `-F` and a browser's `fetch(FormData)` actually put on
the wire — an exact overall length, no per-part length — and is exactly the combination that
exercises the `readUntil` streaming branch while still being allowed its full declared size
under the new Content-Length-bound limit. Documented this reasoning in the test's own comment.

**Side effect:** exercising `android.util.Log.e(...)` in a local JVM unit test for the first
time (no prior test reached the failure path) hit `RuntimeException: Method e in
android.util.Log not mocked` — the stub `android.jar` throws by default. Added
`testOptions.unitTests.isReturnDefaultValues = true` to `app/build.gradle.kts` (a standard,
minimal AGP flag; not a new dependency) so framework calls no-op instead of throwing, as every
prior test's absence of this problem had implicitly assumed.

## IMPORTANT 5 — the notification's Stop still never published state

**Confirmed cause:** `FileServerService.onStartCommand`'s `ACTION_STOP` branch called
`ktorServer.stop()` directly, never going through `ServerRepository` at all. Round 1's
`LifecycleEventEffect(ON_RESUME)` fix only helps when the activity actually passes through
`onPause`/`onResume` — which a notification-shade pull over an *already-visible, already-resumed*
Home screen never does (the shade is an overlay; the underlying activity stays resumed the
whole time). In that scenario the round-1 fix left the dead PIN on screen indefinitely, not
just for one frame, exactly as the review predicted.

**Fix:** `FileServerService` now injects `ServerRepository` and, after `ktorServer.stop()`,
launches `serverRepository.refresh()` on a small `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
(cancelled in `onDestroy`). `refresh()` is already self-healing (sees the engine down, revokes
the PIN, publishes `Stopped`) — it just needed to be called. Confirmed this doesn't create a
service↔repository loop: `refresh()` never calls `launchService(...)`, unlike `stop()`, which
would have (and was deliberately *not* used here for that reason).

## Tests

New/changed tests in `FileRoutesTest.kt`:
- `upload larger than Ktor's default multipart part limit still completes` — rewritten to use
  `rawMultipartFileBody` (see MINOR above); still ~54.4 MiB, still fast.
- `a part's bogus declared length ends the request in a response, not a hang` — new (IMPORTANT 3).
- `upload failure responds with an error and emits a TransferEvent Error` — new (IMPORTANT 3).

Command and output:
```
$ ./gradlew :app:testDebugUnitTest --console=plain
...
BUILD SUCCESSFUL
```
`FileRoutesTest`: 16 tests, 0 failures (was 14 before this round; +2 new). Full suite across
all test classes: **114 tests, 0 failures, 0 errors** (`grep -rh tests= app/build/test-results/testDebugUnitTest/*.xml`).

```
$ ./gradlew :app:assembleDebug --console=plain
...
BUILD SUCCESSFUL
```

Both commands were re-run a second time immediately before committing; both stayed green
(`UP-TO-DATE`, no source changes in between).

## Device re-verification

### Defect 1 (repeat)

Fresh 75 MiB file: `dd if=/dev/urandom of=bigfile2.bin bs=1m count=75` → exactly
`78,643,200` bytes, SHA-256 `57bcbbeb1ca942f4c42025ff582de9f44c1b92316234a123403ac87b0b9e6517`.

Uploaded via Python `requests` (no `Expect: 100-continue`, representative of the shipped
`fetch`-based web UI):
```
status: 200
body: {"files":1,"bytes":78643200}
elapsed: 8.20s
```
Device: `sha256sum /sdcard/DCIM/ferrytest/bigfile2-browserlike.bin` →
`57bcbbeb1ca942f4c42025ff582de9f44c1b92316234a123403ac87b0b9e6517` — **identical**. 78,643,200
bytes in, 78,643,200 bytes out, in 8.20s.

Repeated with curl `-F` (which does send `Expect: 100-continue`) against the same file: bytes
again landed complete and checksum-identical (`sha256sum` on-device matched exactly), but curl
itself still fails to parse the response (`curl: (8) Header without colon`) — confirming the
pre-existing, out-of-scope Ktor CIO 100-continue framing bug documented in
`docs/KNOWN_ISSUES.md` is unchanged by this round's fixes, and that it affects only the
client's ability to parse the acknowledgement, never the bytes actually written to disk.

Test artifacts removed from `/sdcard/DCIM/ferrytest/` after verification.

### Defect 2 (repeat, stricter scenario per review)

This time verified the specific scenario the review named: **Home screen already open and
visible the entire time**, never backgrounded, never relaunched — only the notification shade
pulled down as an overlay on top.

1. Started the server from the Home screen (PIN `976821`, address `192.168.0.6:8080`,
   screenshot-confirmed "● Running").
2. Confirmed via `adb shell` (`get_current_activity` equivalent) that `ru.kryu.ferryfile/.MainActivity`
   was the current foreground activity.
3. Opened the notification shade (`cmd statusbar expand-notifications` — an overlay; does
   **not** pause the underlying activity), expanded the FerryFile notification, located the
   STOP button's exact bounds via `uiautomator dump` (`[35,1440][201,1575]`), and tapped its
   center via `adb shell input tap 118 1507`.
4. Confirmed the server was actually down over the network before touching the UI again:
   `curl --max-time 5 http://192.168.0.6:8080/api/list?path=/` → connection refused (curl exit
   7).
5. Collapsed the shade with a single `BACK` press (not `HOME`, not a task switch) and
   confirmed via the current-activity check that `ru.kryu.ferryfile/.MainActivity` was still
   the foreground activity throughout — it never left.
6. Screenshotted immediately.

Result: **the Home screen showed "● Stopped" and "Start Server" immediately**, having never
left the foreground and without any lifecycle pause/resume ever occurring — proving the fix is
in the service (IMPORTANT 5), not merely papered over by the round-1 `ON_RESUME` effect.
Screenshot: `.superpowers/sdd/2026-09-12-ferryfile-fixes/evidence/defect2-home-stopped-shade-only-no-app-switch.png`.

(One earlier attempt at this same sequence mis-tapped a neighboring Instagram notification and
briefly left the app — that attempt was discarded and redone cleanly from a fresh, confirmed
"Running" state, as described above; the app was never stopped during the mis-tap, only
navigated away from and immediately back to before the real test began.)

## Files changed (round 2)

- `app/build.gradle.kts` — `testOptions.unitTests.isReturnDefaultValues = true`.
- `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt` — raw-channel drain,
  `supervisorScope`, Content-Length-bound `formFieldLimit` + fallback constant,
  `CancellationException` rethrown from both inner catches.
- `app/src/main/java/ru/kryu/ferryfile/service/FileServerService.kt` — injects
  `ServerRepository`, drives `refresh()` after `ACTION_STOP`, owns a `serviceScope` cancelled
  in `onDestroy`.
- `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt` — rewritten
  large-upload test (raw multipart body), two new failure-path tests, `rawMultipartFileBody`
  helper (replacing `multipartBytes`).
- `docs/KNOWN_ISSUES.md` — new; records the Ktor CIO 100-continue framing bug.

## Anything still wrong

Same as round 1: the pre-existing Ktor 3.1.3 `ktor-server-cio` 100-continue response-framing
bug (documented in `docs/KNOWN_ISSUES.md`) is unfixed — it is upstream, not app code, and out
of this task's scope. It affects only curl-like clients that send `Expect: 100-continue`; the
shipped `fetch`-based web UI does not, and the bytes written to disk are unaffected either way
(re-confirmed this round with a fresh checksum-matched curl upload).

No other regressions found. Test artifacts were cleaned up from the device after verification;
the server was left in the Stopped state.

---

# Fix report — round 3 (commit 0c6ad1b)

Re-review of `c719271` confirmed all six round-2 findings addressed with no new Critical/Important
breakage, and asked for three Minor fixes — one a genuine security regression — plus two
accuracy corrections to the docs. All five (three code, two docs) are in `0c6ad1b`.

## (a) Security regression — the declared-length bound had no ceiling

**Finding:** `totalBytes.takeIf { it > 0 } ?: FALLBACK` trusted the client-supplied
`Content-Length` header with no upper bound. A peer sending
`Content-Length: 9223372036854775807` would get `formFieldLimit` set to that value —
effectively unbounded again, and *worse* than the pre-fix state, since before any of this work
Ktor's own hard 50 MiB default capped it regardless of what the client claimed. A filename-less
part is still buffered fully in heap by Ktor (`partToData`'s `readRemaining()`, then duplicated
again as a `String`) before this route ever sees it, so an unbounded declared length is a real
OOM vector against the foreground service, reachable by any peer holding a session.

**Fix:** added `HARD_MULTIPART_PART_LIMIT_CEILING_BYTES = 256L * 1024 * 1024` (256 MiB) and
clamp: `totalBytes.takeIf { it > 0 }?.let { minOf(it, HARD_MULTIPART_PART_LIMIT_CEILING_BYTES) } ?: FALLBACK_MULTIPART_PART_LIMIT_BYTES`.
256 MiB is comfortably above the on-device repro (75 MiB) and any realistic single photo or
video-clip upload this app is for, while being a bounded, finite number a process with no
`android:largeHeap` declared has some chance of surviving if a worst-case non-file part were
ever pushed all the way to it — a deliberate trade-off documented in the constant's doc comment,
not an attempt to make that scenario free. The existing fallback behaviour for an absent,
unparseable, zero, or negative `Content-Length` is untouched (those never reach the clamp at
all, since `takeIf { it > 0 }` filters them out first).

## (b) `FileServerService` — refresh() raced scope cancellation

**Finding:** `serviceScope.launch { serverRepository.refresh() }` immediately followed by
`stopSelf()`, with `onDestroy()` cancelling `serviceScope`. With the default coroutine start
mode, nothing in the code *guaranteed* the launched coroutine had begun executing before a
sufficiently fast cancellation — it worked in practice only because `stopSelf()`'s message
can't be processed until `onStartCommand` returns and because `refresh()`'s Stopped-path body
happens to have no real suspension point, neither of which the code enforced.

**Fix:** `serviceScope.launch(start = CoroutineStart.UNDISPATCHED) { serverRepository.refresh() }`.
`UNDISPATCHED` begins running the coroutine body immediately, inline, before the `launch()`
call itself returns control to `onStartCommand` — and since Android services process lifecycle
callbacks one at a time on the main thread, `onDestroy()` cannot run concurrently with (or
before) this synchronous call completes, so at the moment this line executes the scope is
guaranteed not yet cancelled. Comment added explaining exactly this ordering guarantee rather
than leaving it as an incidental fact about the current code path.

## (c) Replaced the global test-stub flag with a logging seam

**Finding:** `testOptions.unitTests.isReturnDefaultValues = true` (added in round 2 solely so
`Log.e` wouldn't throw under the stub `android.jar`) is module-wide and permanent — it silently
converts *every* unmocked Android framework call in the whole local test suite into a default
return instead of a loud "Method not mocked" failure, including ones the review confirmed are
currently masked by other means (e.g. `Uri.parse` in `SafRootsProvider`/`SafPermissionManager`
behind `runCatching`, which would turn a future missing mock into a silent "no roots" pass
instead of an exception).

**Fix:** `configureFileRoutes` now takes a `logError: (String, Throwable) -> Unit` parameter
defaulting to the original `Log.e(UPLOAD_LOG_TAG, message, cause)` call; all three
failure-logging sites in the upload route now call `logError(...)` instead of `Log.e(...)`
directly. `FileRoutesTest` passes its own `logError` (appends to a `loggedErrors` list) instead
of relying on the default, and the existing storage-failure test now also asserts a matching
entry was recorded — proving the seam is actually usable, not just present. Reverted
`app/build.gradle.kts`'s `testOptions` block entirely. **Confirmed the suite still passes with
the flag gone** (114 tests, 0 failures) — that is the actual proof the seam removed the
dependency on the framework stub, not merely an assertion that it should.

## Accuracy corrections (no code)

- `device-fixes-report.md`'s round-2 entry claimed the malformed-body test "caught both the
  Critical drain bug and the job-cancellation bug." Corrected in place (see the round-2 section
  above, now amended): the test only demonstrates the job-cancellation (`supervisorScope`) fix
  — its body is ~230 bytes, so the backed-up-socket condition the raw-channel drain exists for
  is structurally absent on the in-process test engine, and the same test would have passed
  with the old, broken (`multipart.readPart()`-based) drain in place too. The drain fix rests
  on the source-level analysis of `CIOMultipartDataBase.readPart()` and the on-device
  re-verification of a real 75 MiB upload completing, not on this suite.
- `docs/KNOWN_ISSUES.md`: fixed the 100-continue entry's claim that the broken interceptor
  "runs ... before any application route code" — it's a receive-pipeline interceptor, so it
  actually fires during the route's own `call.receiveMultipart()` call, not before it (still
  entirely inside Ktor's own machinery either way, so the conclusion — nothing in `FileRoutes.kt`
  can fix it — is unchanged). Added a new entry documenting that a scripted client using
  `Transfer-Encoding: chunked` (no `Content-Length`) is now capped at the 8 MiB fallback per
  part, down from Ktor's 50 MiB default; the shipped `fetch`-based web UI is unaffected since
  `FormData` bodies always carry a computed `Content-Length`.

## Tests and build

```
$ ./gradlew :app:testDebugUnitTest --console=plain
...
BUILD SUCCESSFUL
```
114 tests, 0 failures, 0 errors — run with the `testOptions.unitTests.isReturnDefaultValues`
flag fully removed, confirming the `logError` seam actually decouples the suite from the
`android.util.Log` stub rather than merely coexisting with the flag.

```
$ ./gradlew :app:assembleDebug --console=plain
...
BUILD SUCCESSFUL
```

## Device re-verification

**Not re-run**, per the coordinator's explicit condition ("provided none of these three changes
alters upload behaviour on the success path"), which holds:
- (a) the clamp only engages when the declared `Content-Length` exceeds 256 MiB; the 75 MiB
  on-device case (and any realistic real upload) is unaffected — `minOf(75_MiB, 256_MiB)` is
  still `75_MiB`, identical to round 2's behaviour.
- (b) only changes *when*, within the STOP path, a coroutine starts — no upload-path code touched.
- (c) is a pure logging refactor (production default is the exact same `Log.e` call as before)
  plus a test-only Gradle option revert — zero behavioural change in the app itself.

## Files changed (round 3)

- `app/build.gradle.kts` — reverted the `testOptions` block added in round 2.
- `app/src/main/java/ru/kryu/ferryfile/server/routes/FileRoutes.kt` —
  `HARD_MULTIPART_PART_LIMIT_CEILING_BYTES` + clamp, `logError` parameter replacing direct
  `Log.e` calls at all three sites.
- `app/src/main/java/ru/kryu/ferryfile/service/FileServerService.kt` — `CoroutineStart.UNDISPATCHED`
  on the refresh launch, with a comment on why ordering is now guaranteed.
- `app/src/test/java/ru/kryu/ferryfile/server/routes/FileRoutesTest.kt` — `loggedErrors`
  capture, `logError` wired into `withApp`, one new assertion on it.
- `docs/KNOWN_ISSUES.md` — phrasing fix + new chunked-upload entry.
- `.superpowers/sdd/2026-09-12-ferryfile-fixes/device-fixes-report.md` — corrected the
  round-2 claim about what the malformed-body test proves (this file).

## Anything still wrong

Same as rounds 1–2: the pre-existing, upstream Ktor 3.1.3 `ktor-server-cio` 100-continue
response-framing bug remains unfixed (out of scope, not app code), now documented in
`docs/KNOWN_ISSUES.md` alongside the chunked-upload fallback-limit behaviour noted above. No
other regressions found.
