# Parallel Range-GET File Downloader

[![CI](https://github.com/lmnst/java-parallel-downloader/actions/workflows/ci.yml/badge.svg)](https://github.com/lmnst/java-parallel-downloader/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21+-orange?logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

A Java 21 library and CLI for parallel, resumable, integrity-checked
HTTP file downloads. Either the destination file matches a known
SHA-256, or no artifact is written.

## Highlights

- **Parallel** ranged GETs over virtual threads, bounded by a
  configurable parallelism semaphore. ~3.5x speedup over a single
  stream on a high-RTT link (see [Performance](#performance)).
- **Atomic commit**: a temp `<dest>.part` is `fsync`ed and
  `Files.move`d with `ATOMIC_MOVE` only after every chunk has
  written and the digest has been verified. The destination path
  never holds a corrupt or partial file.
- **Resumable** via a sidecar JSON manifest, fenced by `If-Range`
  so a changed source resource fails fast instead of silently
  splicing old and new bytes.
- **Chaos-tested**: 120 seeded runs inject 14 fault classes into
  every HTTP GET; the suite asserts the same invariant on every
  seed. Success means correct bytes; failure means a typed error
  with no leftover artifact.
- **Zero runtime dependencies.** The shipped JAR contains nothing
  outside the JDK.

## Quick start

```bash
./gradlew installDist
DL=build/install/parallel-downloader/bin/parallel-downloader

# Local server with a 64 MiB random corpus
mkdir -p /tmp/corpus
head -c $((64 * 1024 * 1024)) /dev/urandom > /tmp/corpus/test.bin
docker run --rm -d -p 8080:80 \
    -v /tmp/corpus:/usr/local/apache2/htdocs/ \
    --name dl-httpd httpd:2.4

$DL --url http://localhost:8080/test.bin --out /tmp/dl.bin --report json
```

```json
{
  "status": "success",
  "file": "/tmp/dl.bin",
  "bytes": 67108864,
  "elapsedMs": 234,
  "chunks": 8
}
```

`just demo` runs the same end-to-end with a generated SHA-256 check
and teardown. Full CLI reference: [`docs/USAGE.md`](docs/USAGE.md).

## Library usage

```java
try (Downloader dl = Downloader.create(DownloaderOptions.builder()
        .parallelism(8)
        .chunkSize(8 * 1024 * 1024)
        .expectedDigest(ExpectedDigest.sha256(hex))
        .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
        .build())) {

    DownloadResult result = dl.download(uri, dest);
    switch (result) {
        case DownloadResult.Success s -> System.out.println(s.bytes());
        case DownloadResult.Failure f -> System.err.println(f.error());
    }
}
```

| Type | Role |
|---|---|
| `Downloader` | `download` / `downloadAsync` / `close` |
| `DownloaderOptions` | Record + builder. `expectedDigest`, `resumeStrategy`, `progressListener`. |
| `DownloadResult` | Sealed: `Success` or `Failure`. |
| `DownloadError` | Enum: `HTTP_ERROR`, `IO_ERROR`, `SIZE_MISMATCH`, `INTEGRITY_FAILURE`, `RESOURCE_CHANGED`, `CANCELLED`, `TIMEOUT`, `RANGES_NOT_SUPPORTED`. |
| `ProgressListener` | SPI; `NO_OP` is the default. |
| `ProgressEvent` | Sealed: `Started`, `ChunkCompleted`, `Failed`, `Finished`. |

## Architecture

![Download lifecycle sequence diagram](docs/architecture.svg)

Source: [`docs/architecture.puml`](docs/architecture.puml). Re-render
with `java -jar plantuml.jar -tsvg docs/architecture.puml`.

## Performance

64 MiB file served from `httpd:2.4` with 50 ms one-way `netem`
delay, 4 MiB chunks, median of three runs. Reproducible without
Docker via `./gradlew jmh`.

| `--parallelism` | Median time | Throughput | Speedup |
|---:|---:|---:|---:|
| 1 (single-stream) | 2929 ms | 21.8 MiB/s | 1.00x |
| 4                 | 1298 ms | 49.3 MiB/s | 2.26x |
| 8                 |  995 ms | 64.3 MiB/s | 2.94x |
| 16                |  827 ms | 77.4 MiB/s | 3.54x |

The shape of the curve is the load-bearing claim, not the absolute
numbers. Speedup compounds until the BDP of the link is filled.

For a zero-RTT loopback comparison against `curl` and `wget`, see
[`docs/COMPARISON.md`](docs/COMPARISON.md).

## Correctness model

Three claims, each enforced at one place in the code and verified
under seeded fault injection.

### 1. Probe chunk before parallel fan-out

Some servers advertise `Accept-Ranges: bytes` in HEAD but ignore
the `Range` header on a subsequent GET, returning the full body
with status `200`. If N parallel chunks each receive the full body
and write at their own offset, the destination file is corrupted
silently.

A synchronous probe chunk at offset 0 runs before any other chunks
fan out. If the probe returns `200`, the body is committed as the
complete download; no parallel writes ever happen, so corruption is
impossible. If the probe returns `206`, range support is confirmed
and the remaining chunks fan out under the parallelism semaphore.

### 2. Integrity is verified before the atomic move

When `expectedDigest(...)` is set, the temp file is streamed
through a `MessageDigest` after the last chunk completes and
*before* `Files.move(..., ATOMIC_MOVE)`. A mismatch fails with
`INTEGRITY_FAILURE` and deletes the temp file. The destination
path is never touched on failure, so a downstream watcher cannot
observe a wrong-bytes file under the right name even transiently.

### 3. Resumption is fenced by `If-Range`

In `RESUME_IF_VALID` mode, a `<dest>.part.json` sidecar manifest is
written after each chunk's successful write and fsync. It records
URL, ETag (or `Last-Modified`), `Content-Length`, chunk size, and a
hex bitmap of completed chunks.

On retry, only missing chunks are re-fetched, and every ranged GET
carries `If-Range: <validator>`. A `200` on a ranged GET with
`If-Range` set means the server has replaced the resource; the
adapter surfaces this and the downloader fails fast with
`RESOURCE_CHANGED` rather than splicing old and new bytes.

## Testing

| Layer | Count | What it covers |
|---|---:|---|
| Unit | 153 | Range planner, manifest, file assembler, retry policy, JSON encoder, CLI parser. |
| Property | (in unit) | `RangePlanner` covers `totalBytes` exactly across random sizes. |
| Integration | 4 | Real `httpd:2.4` via Testcontainers; full lifecycle through CLI binary. |
| Chaos | 120 seeds | 14 fault classes per HTTP GET; invariants asserted on every seed. |

The chaos invariant: *Success implies the destination matches the
source SHA-256; Failure implies a typed `DownloadError` with no
leftover artifact at the destination.* No "best-effort" middle
ground is permitted.

## Build

```bash
./gradlew check                # all tests except chaos
./gradlew test -PchaosTests    # chaos suite (~30 s)
./gradlew installDist          # CLI launcher under build/install/
./gradlew javadoc              # publishable API docs
```

CI runs the full check on Linux, macOS, and Windows on every push.
Requires Java 21 or newer; Gradle 8.13 wrapper included.

## Further reading

- [`DESIGN.md`](DESIGN.md): trade-offs, resumption state machine,
  the chaos invariant, what was deliberately left out.
- [`docs/USAGE.md`](docs/USAGE.md): full CLI reference, exit-code
  table, library and listener examples.
- [`docs/COMPARISON.md`](docs/COMPARISON.md): vs `curl`, `wget` on
  zero-RTT loopback.
- [`docs/STORY-TESTCONTAINERS-DOCKER.md`](docs/STORY-TESTCONTAINERS-DOCKER.md):
  debugging episode about a silent integration-test skip on Docker
  Engine 29.

## License

MIT. See [`LICENSE`](LICENSE).
