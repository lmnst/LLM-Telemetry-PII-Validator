# Parallel Range-GET File Downloader

A Java 21 library and CLI for parallel, resumable, integrity-checked HTTP file
downloads. Designed as a data-ingestion primitive: arbitrary-sized files moved
across imperfect networks, with strong correctness guarantees — every download
ends with either the destination file matching a known SHA-256 digest, or no
artifact written. No runtime dependencies; the library is JUnit-tested
hermetically and stress-tested under random fault injection.

## Why this design

The failure modes that matter for ingest aren't the happy path — they're:

- **Upstream replacement** of the resource mid-download → validated via
  `If-Range` per chunk; mismatch surfaces a typed `RESOURCE_CHANGED` rather
  than silently merging old and new bytes.
- **Partial network failure** → resumable via a sidecar manifest
  (`<dest>.part.json`); only missing chunks are re-fetched on retry.
- **Undetected corruption** → caller can supply an expected SHA-256; the
  computed digest is verified _before_ the atomic move so a corrupt file is
  never visible at the destination path.
- **Silent truncation / wrong-range responses** → per-chunk byte-count and
  `Content-Range` validation; both are protocol-level pre-conditions for
  commit and never run inside the retry loop.
- **Servers that lie about Range support** → chunk 0 is downloaded as a
  synchronous probe before any parallel work starts; a `200` body becomes the
  full download instead of corrupting eight overlapping writes.

Every one of those is a typed `DownloadError`; none can leave a half-written
destination behind.

## Requirements

- Java 21+
- Gradle 8.13+ (wrapper included — no separate install needed)

## Quick demo

The CLI downloads a file in parallel and prints a structured report.

```bash
# 1. Generate a 64 MiB random file
mkdir -p /tmp/corpus && head -c $((64 * 1024 * 1024)) /dev/urandom > /tmp/corpus/test.bin

# 2. Serve it from an Apache container
docker run --rm -d -p 8080:80 -v /tmp/corpus:/usr/local/apache2/htdocs/ --name dl-httpd httpd:2.4

# 3. Download it
./gradlew run --args="--url http://localhost:8080/test.bin --out /tmp/downloaded.bin --report json"

# 4. Tear down
docker stop dl-httpd
```

Sample JSON output:

```json
{"status":"success","file":"/tmp/downloaded.bin","bytes":67108864,"elapsedMs":234,"chunks":8,
 "chunkDetails":[{"index":0,"offset":0,"length":8388608,"attempts":1,"durationMs":30}, ...]}
```

Run `./gradlew run --args="--help"` for the full flag and exit-code reference.
A `justfile` is included with `just demo`, `just test`, `just integration`,
`just chaos` shortcuts.

## Public API

```
Downloader            — download(URI, Path) / downloadAsync(URI, Path) / close()
DownloaderOptions     — record + Builder; expectedDigest, resumeStrategy, progressListener
DownloadResult        — sealed: Success | Failure
DownloadError         — enum: HTTP_ERROR | IO_ERROR | SIZE_MISMATCH | INTEGRITY_FAILURE
                              | RESOURCE_CHANGED | CANCELLED | TIMEOUT | RANGES_NOT_SUPPORTED
DownloadHandle        — join() / joinWithTimeout(Duration) / cancel() / state()
HttpAdapter           — inject a custom adapter (e.g. for tests)
HttpStatusException   — Failure.cause() for HTTP_ERROR; carries statusCode()
Algorithm             — enum: SHA_256 (single member; extensible)
ExpectedDigest        — record (algorithm, bytes); validated in compact ctor
ResumeStrategy        — enum: FRESH (default) | RESUME_IF_VALID
ProgressListener      — onProgress(ProgressEvent); NO_OP default
ProgressEvent         — sealed: Started | ChunkCompleted | Failed | Finished
ByteRange             — record (offset, length); inclusive HTTP byte semantics
cli.Main              — entry point for ./gradlew run
```

## Behavior matrix

| Scenario | Behaviour |
|---|---|
| Server supports `Accept-Ranges: bytes` | Parallel range-GET; concurrency bounded by `parallelism` |
| Server ignores Range header (returns `200`) | Detected via probe chunk; falls back to single-stream; no corruption possible |
| `206` with mismatched `Content-Range` | Detected and rejected before commit |
| Transient errors (408/429/5xx, `IOException`, timeout) | Exponential backoff with full jitter; honours `Retry-After` |
| Non-retryable errors (400/401/403/404/…) | Immediate typed failure |
| Cancellation | Cooperative: observed between buffer reads and before each chunk attempt; FRESH deletes `.part`, RESUME preserves; destination untouched |
| Any failure | FRESH: `.part` and `.part.json` deleted. RESUME: both preserved. Destination never written or corrupted in either mode. |
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

## Resumability

Pass `--resume` (library: `DownloaderOptions.resumeStrategy(RESUME_IF_VALID)`).
After every chunk's successful write+verify the downloader flushes a sidecar
`<dest>.part.json` (write `.tmp`, fsync, rename) recording URL, ETag,
Last-Modified, Content-Length, chunk size, and a hex bitmap of completed
chunks. On restart the sidecar is validated against a fresh `HEAD` — match
replays only missing chunks; any drift in those validators surfaces a
`RESOURCE_CHANGED` failure (exit 6) with the sidecar preserved so the caller
can decide to delete it and retry from scratch. Each ranged GET in resume
mode sends `If-Range`; a `200` response means the validator no longer
matches and is reported as `RESOURCE_CHANGED` rather than silently merging
old and new bytes.

```bash
# First attempt is interrupted
./gradlew run --args="--url https://example.com/big.bin --out /tmp/big.bin --resume"

# Re-run with the same flag — only missing chunks are re-fetched:
./gradlew run --args="--url https://example.com/big.bin --out /tmp/big.bin --resume"
```

`FRESH` mode (the default) ignores any existing `.part` / `.part.json` files.
Single-stream downloads cannot be resumed and ignore the flag.

## Integrity verification

Pass `--sha256 <64-hex>` (library:
`DownloaderOptions.expectedDigest(Algorithm.SHA_256, byte[])`). After all
chunks complete the temp file is streamed through a 64 KiB single-pass
`MessageDigest`; mismatch fails the download with exit 4 / `INTEGRITY_FAILURE`
_before_ the atomic move, so the destination path is never written.
`DownloadResult.Success.sha256()` returns `Optional<byte[]>` — populated when
verification ran, empty otherwise.

```bash
$ ./gradlew run --args="--url http://localhost:8080/test.bin --out /tmp/x.bin \
    --sha256 0000000000000000000000000000000000000000000000000000000000000000 --report json"
{"status":"failure","error":"INTEGRITY_FAILURE","exitCode":4,
 "cause":"integrity check failed: expected SHA-256 0000... got b5d4..."}
```

## Progress and reporting

Set a `ProgressListener` on the options (the CLI auto-installs one). Events:

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
implementations do not need to be thread-safe with respect to each other. A
throwing listener is caught and the first exception is logged once to
`System.err` with the event class name.

In `--report text` the CLI renders a live `\r`-overwriting status line
(`<bytes>/<total> @ <MiB/s> ETA <h:mm:ss> (n/N chunks)`); in `--report json`
the per-chunk attempts and durations are aggregated into the final report's
`chunkDetails` array.

## Chaos testing

A property-based suite (`-PchaosTests`) runs 120 seeded downloads against an
in-process `HttpAdapter` that, on every GET, samples one of fourteen fault
classes (HTTP 408/429/5xx, 200-on-ranged-GET, truncated bodies, malformed
or mismatching `Content-Range`, mid-body `IOException`, slowloris, jitter)
from a deterministic RNG. Each run asserts the headline invariant: every
download ends with either `Success` and the correct bytes, or `Failure`
with a typed `DownloadError` and no leftover artifacts. Total runtime: ~1 s.

```bash
./gradlew test -PchaosTests
```

See **[Chaos testing](DESIGN.md#chaos-testing)** in DESIGN.md for the fault
catalog, the seed-stability promise, and the past-offenders log.

## Library usage

```java
import com.example.downloader.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

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
                "Failed: " + f.error() + " — " + f.cause().getMessage());
    }
}
```

## Design notes

See [DESIGN.md](DESIGN.md) for trade-off analysis, the resumption state
machine diagram, the chaos invariant, and what was deliberately left out.
