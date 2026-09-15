# Known issues

Состояние проверено 2026-09-16 на HEAD `9365991`. Production-сервер приложения сейчас работает на Netty (`ktor-server-netty:3.1.3`); `ktor-server-cio` отсутствует в release runtime classpath.

## Историческая проблема Ktor CIO `100 Continue` (не относится к текущему production runtime)

Эта запись сохранена для объяснения старого device report и не является действующей проблемой текущей сборки. Она была обнаружена, когда приложение использовало Ktor CIO. Если CIO не будет возвращён, исправлять или эскалировать её для FerryFile не нужно.

**Symptom:** a client that sends `Expect: 100-continue` (curl does this automatically for
request bodies above ~1 MiB; browsers' `fetch`/`XHR` do not) fails to parse the server's
response to a large `POST /api/upload`, e.g. `curl: (8) Header without colon`.

**Cause in the old CIO runtime:** `CIOApplicationEngine.addHandlerForExpectedHeader` wrote `HTTP/1.1 100 Continue\r\n`
with a single trailing CRLF instead of the blank-line-terminated interim response HTTP
requires, so the real final response that follows gets parsed as more of the same interim
response's (never-closed) header block. This is a receive-pipeline interceptor, so it actually
fires during the route's own `call.receiveMultipart()` call, not before it — but it's still
entirely inside Ktor's engine/pipeline machinery, so nothing in `FileRoutes.kt` can cause or
fix it; the old CIO build would have needed a Ktor version bump or engine patch.

**Impact:** the upload still lands byte-for-byte correct on disk in every case observed; only
the client's ability to parse the *acknowledgement* is affected. The shipped web UI uses
`fetch` and does not send `Expect: 100-continue`, so it does not hit this. The danger is a
scripted client (curl, or anything else that sends `Expect: 100-continue`) reporting a false
failure and retrying an upload that already succeeded.

**Workaround:** disable the header client-side, e.g. `curl -H 'Expect:' -F file=@... ...`.

## Chunked (no `Content-Length`) uploads are capped at 8 MiB per part

`/api/upload` bounds Ktor's per-part multipart limit by the request's own declared
`Content-Length` (see `FALLBACK_MULTIPART_PART_LIMIT_BYTES` in `FileRoutes.kt`). A client that
sends `Transfer-Encoding: chunked` instead of a `Content-Length` — which curl and browsers
never do for a `FormData`/`-F` upload, since both compute the size upfront, but a hand-rolled
scripted client could — falls back to an 8 MiB ceiling per part, down from Ktor's own 50 MiB
default. The shipped web UI is unaffected: `fetch` with a `FormData` body always sends a
computed `Content-Length`.

## Single files above 256 MiB are rejected on upload

`/api/upload` clamps Ktor's per-part multipart limit at `HARD_MULTIPART_PART_LIMIT_CEILING_BYTES`
in `FileRoutes.kt`, so a single file above 256 MiB fails to upload.

The 256 MiB value applies to one multipart part, not to the total request. There is currently
no server-side limit for the total number of files, total request size, or concurrent uploads.

**Cause:** Ktor 3.1.3's `receiveMultipart(formFieldLimit = ...)` enforces one limit across
every part of a multipart request at the channel-read level, with no per-part-type override —
and a part without a filename is buffered whole in memory by Ktor itself before the route ever
sees it. Some finite ceiling is therefore unavoidable without a custom multipart parser; see
the KDoc on `HARD_MULTIPART_PART_LIMIT_CEILING_BYTES` for the full trade-off.

**Impact:** a multi-minute 4K video (routinely several hundred MB) is rejected outright. The
web UI shows a prompt error, not a hang.

**Follow-up:** raising the ceiling properly needs a custom multipart parser that streams
file parts to disk without Ktor's whole-request limit applying to them; that is a separate
task and has not been done here.

## Failed or cancelled uploads can leave a partial file

The upload path creates the destination file before streaming bytes into it. If storage fails or
the client disconnects after creation, the current SAF repository closes the stream but does not
remove the partially written `DocumentFile`. The readiness checklist tracks cleanup, free-space
handling, cancellation, and a user-visible policy for partial results.
