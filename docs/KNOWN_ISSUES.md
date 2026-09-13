# Known issues

## Ktor CIO `100 Continue` response framing (upstream, not app code)

**Symptom:** a client that sends `Expect: 100-continue` (curl does this automatically for
request bodies above ~1 MiB; browsers' `fetch`/`XHR` do not) fails to parse the server's
response to a large `POST /api/upload`, e.g. `curl: (8) Header without colon`.

**Cause:** `CIOApplicationEngine.addHandlerForExpectedHeader` writes `HTTP/1.1 100 Continue\r\n`
with a single trailing CRLF instead of the blank-line-terminated interim response HTTP
requires, so the real final response that follows gets parsed as more of the same interim
response's (never-closed) header block. This is a receive-pipeline interceptor, so it actually
fires during the route's own `call.receiveMultipart()` call, not before it — but it's still
entirely inside Ktor's engine/pipeline machinery, so nothing in `FileRoutes.kt` can cause or
fix it; it needs a Ktor version bump, which has not been evaluated here.

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
