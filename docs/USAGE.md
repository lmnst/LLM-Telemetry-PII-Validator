# CLI and library reference

The README covers the design at a glance; this page is the full
flag-by-flag and type-by-type reference for callers.

## Requirements

- Java 21+
- Gradle 8.13+ (wrapper included)

## CLI

`build/install/parallel-downloader/bin/parallel-downloader` after
`./gradlew installDist`. The wrapper resolves `java` via `$JAVA_HOME`
or `$PATH`; either must point at a JDK 21+ installation, since the
binaries are class-file 65.

```
--url <URI>              source URL
--out <path>             destination file
--chunk-size <bytes>     bytes per chunk (default 8M; suffix K/M/G accepted)
--parallelism <int>      max concurrent ranged GETs (default 8)
--sha256 <64-hex>        expected digest; verified before atomic move
--resume                 enable sidecar-manifest resumption
--report text|json       output format (default text)
--help                   print the full reference
```

Sample JSON output:

```json
{"status":"success","file":"/tmp/downloaded.bin","bytes":67108864,"elapsedMs":234,"chunks":8,
 "chunkDetails":[{"index":0,"offset":0,"length":8388608,"attempts":1,"durationMs":30}, ...]}
```

> **Note on `./gradlew run` and exit codes.** Gradle wraps the JVM's
> exit code, so a typed CLI failure (e.g. exit 4 for
> `INTEGRITY_FAILURE`) surfaces as `BUILD FAILED, exit 1` and the
> structured report is swallowed by Gradle's banner. Invoke
> `build/install/parallel-downloader/bin/parallel-downloader` directly
> to preserve per-error exit codes.

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | Success |
| 1 | Generic failure (HTTP 4xx other than 408 / 429, `RANGES_NOT_SUPPORTED`) |
| 2 | Usage / argument error (parse failure on `--url`, `--chunk-size`, `--sha256`, `--report`, etc.) |
| 3 | Transient or network failure (I/O error, request timeout, HTTP 5xx after retries are exhausted) |
| 4 | Integrity failure (digest mismatch on `--sha256`, or size mismatch against `Content-Length`) |
| 5 | Cancelled |
| 6 | Resource changed mid-resume |

The mapping is enforced by `Main.exitCodeFor` and locked in by
`MainCliTest.exitCodeFor_eachDownloadError_mapsAsDocumented`; the
table above is the documentation, the code is the source of truth.

## Behavior matrix

| Scenario | Behaviour |
|---|---|
| Server supports `Accept-Ranges: bytes` | Parallel range-GET; concurrency bounded by `parallelism` |
| Server ignores Range header (returns `200`) | Detected via probe chunk; falls back to single-stream; no corruption possible |
| `206` with mismatched `Content-Range` | Detected and rejected before commit |
| Transient errors (408/429/5xx, `IOException`, timeout) | Exponential backoff with full jitter; honours `Retry-After` |
| Non-retryable errors (400/401/403/404/...) | Immediate typed failure |
| Cancellation | Cooperative: observed between buffer reads and before each chunk attempt; FRESH deletes `.part`, RESUME preserves; destination untouched |
| Any failure | FRESH: `.part` and `.part.meta` deleted. RESUME: both preserved. Destination never written or corrupted in either mode. |
| Destination already exists | Success replaces it atomically; failure leaves original intact |
| Destination is a directory | Immediate typed `IO_ERROR` |
| Resource changed mid-resume | Detected via `If-Range` and manifest validation; fails fast with `RESOURCE_CHANGED` (exit 6); sidecar preserved |

## Default options

| Option | Default | Notes |
|---|---|---|
| `chunkSize` | 8 MiB | Amortises TCP slow-start |
| `parallelism` | 8 | Caps concurrent GETs |
| `connectTimeout` | 10 s | |
| `requestTimeout` | 60 s | Per chunk including body transfer |
| `maxRetriesPerChunk` | 3 | |
| `retryBaseDelay` | 200 ms | Exponential + full jitter, capped at 30 s |
| `expectedDigest` | none | Set via `expectedDigest(Algorithm.SHA_256, byte[])` |
| `resumeStrategy` | `FRESH` | `RESUME_IF_VALID` opts in to sidecar resumption |
| `progressListener` | `NO_OP` | Receives `ProgressEvent`s on a single virtual thread |

## Public type list

```
Downloader            download(URI, Path) / downloadAsync(URI, Path) / close()
DownloaderOptions     record + Builder; expectedDigest, resumeStrategy, progressListener
DownloadResult        sealed: Success | Failure
DownloadError         enum: HTTP_ERROR | IO_ERROR | SIZE_MISMATCH | INTEGRITY_FAILURE
                            | RESOURCE_CHANGED | CANCELLED | TIMEOUT | RANGES_NOT_SUPPORTED
DownloadHandle        join() / joinWithTimeout(Duration) / cancel() / state()
HttpAdapter           inject a custom adapter (e.g. for tests)
HttpStatusException   Failure.cause() for HTTP_ERROR; carries statusCode()
Algorithm             enum: SHA_256 (single member; extensible)
ExpectedDigest        record (algorithm, bytes); validated in compact ctor
ResumeStrategy        enum: FRESH (default) | RESUME_IF_VALID
ProgressListener      onProgress(ProgressEvent); NO_OP default
ProgressEvent         sealed: Started | ChunkCompleted | Failed | Finished
ByteRange             record (offset, length); inclusive HTTP byte semantics
cli.Main              entry point for the installed CLI binary
```

## Library usage

```java
import io.github.lmnst.downloader.*;
import java.net.URI;
import java.nio.file.Path;

DownloaderOptions opts = DownloaderOptions.builder()
        .chunkSize(8 * 1024 * 1024L)
        .parallelism(8)
        .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
        .expectedDigest(Algorithm.SHA_256, expectedBytes)
        .build();

try (Downloader downloader = new Downloader(opts)) {
    DownloadResult result = downloader.download(
            URI.create("https://example.com/large-file.bin"),
            Path.of("/tmp/large-file.bin"));
    switch (result) {
        case DownloadResult.Success s -> System.out.printf(
                "%d bytes in %s (%d chunks)%n", s.bytes(), s.elapsed(), s.chunks());
        case DownloadResult.Failure f -> System.err.println(
                "Failed: " + f.error() + ", " + f.cause().getMessage());
    }
}
```

## Progress listener

```java
DownloaderOptions.builder()
    .progressListener(event -> {
        switch (event) {
            case ProgressEvent.Started s        -> System.out.println("starting " + s.totalBytes() + "B");
            case ProgressEvent.ChunkCompleted c -> System.out.println("chunk " + c.index() + " ok in " + c.duration());
            case ProgressEvent.Finished f       -> System.out.println("done");
            case ProgressEvent.Failed f         -> System.err.println("failed: " + f.error());
        }
    })
    .build();
```

Events are dispatched from a single virtual thread, so listener
implementations do not need to be thread-safe with respect to each
other. A throwing listener is caught and the first exception is
logged once to `System.err` with the event class name.

In `--report text`, the CLI renders a live `\r`-overwriting status
line: `<bytes>/<total> @ <MiB/s> ETA <h:mm:ss> (n/N chunks)`. In
`--report json`, the per-chunk attempts and durations are aggregated
into the final report's `chunkDetails` array.

## Resumability

Pass `--resume` (library:
`DownloaderOptions.resumeStrategy(RESUME_IF_VALID)`). After every
chunk's successful write+verify, the downloader flushes a sidecar
`<dest>.part.meta` (write `.tmp`, fsync, rename) recording URL,
ETag, Last-Modified, Content-Length, chunk size, and a hex bitmap of
completed chunks. On restart, the sidecar is validated against a
fresh `HEAD`: match replays only missing chunks; any drift in those
validators surfaces a `RESOURCE_CHANGED` failure (exit 6) with the
sidecar preserved so the caller can decide to delete it and retry
from scratch. Each ranged GET in resume mode sends `If-Range`; a
`200` response means the validator no longer matches and is reported
as `RESOURCE_CHANGED` rather than silently merging old and new
bytes.

```bash
$DL --url https://example.com/big.bin --out /tmp/big.bin --resume
# Re-run with the same flag; only missing chunks are re-fetched.
$DL --url https://example.com/big.bin --out /tmp/big.bin --resume
```

`FRESH` mode (the default) ignores any existing `.part` /
`.part.meta` files. Single-stream downloads cannot be resumed and
ignore the flag.

## Integrity verification

Pass `--sha256 <64-hex>` (library:
`DownloaderOptions.expectedDigest(Algorithm.SHA_256, byte[])`).
After all chunks complete, the temp file is streamed through a
64 KiB single-pass `MessageDigest`; mismatch fails the download with
exit 4 / `INTEGRITY_FAILURE` *before* the atomic move, so the
destination path is never written. `DownloadResult.Success.sha256()`
returns `Optional<byte[]>`, populated when verification ran, empty
otherwise.

```bash
$ $DL --url http://localhost:8080/test.bin --out /tmp/x.bin \
      --sha256 0000000000000000000000000000000000000000000000000000000000000000 --report json
{"status":"failure","error":"INTEGRITY_FAILURE","exitCode":4,
 "cause":"integrity check failed: expected SHA-256 0000... got b5d4..."}
$ echo $?
4
```

## Chaos testing

A property-based suite (`-PchaosTests`) runs 120 seeded downloads
against an in-process `HttpAdapter` that, on every GET, samples one
of fourteen fault classes (HTTP 408/429/5xx, 200-on-ranged-GET,
truncated bodies, malformed or mismatching `Content-Range`, mid-body
`IOException`, slowloris, jitter) from a deterministic RNG. Each run
asserts the headline invariant: every download ends with either
`Success` and the correct bytes, or `Failure` with a typed
`DownloadError` and no leftover artifacts. Total runtime: ~1 s.

```bash
./gradlew test -PchaosTests
```

See [Chaos testing](../DESIGN.md#chaos-testing) in DESIGN.md for the
fault catalog, the seed-stability promise, and the past-offenders
log.

## How the README's Performance numbers were measured

The container is configured with `--cap-add=NET_ADMIN` so a single
`tc qdisc` rule injects RTT inside its network namespace; nothing on
the host is touched.

```bash
docker run --rm -d --name dl-bench --cap-add=NET_ADMIN -p 8090:80 \
    -v /tmp/bench-corpus:/usr/local/apache2/htdocs/ httpd:2.4
docker exec dl-bench bash -c \
    "apt-get update -qq && apt-get install -y -qq iproute2 && \
     tc qdisc add dev eth0 root netem delay 50ms"

for P in 1 4 8 16; do
    rm -f /tmp/dl.bin
    "$DL" --url http://localhost:8090/test.bin --out /tmp/dl.bin \
        --parallelism "$P" --chunk-size 4M --report json
done
```

Numbers were collected on an Apple Silicon laptop in May 2026;
absolute times will vary by hardware, but the relative shape
(super-linear gain through `p=4`, plateau around `p=8`) is the
load-bearing claim.
