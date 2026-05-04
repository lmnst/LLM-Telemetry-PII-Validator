# Design Notes

## Language & build choice

**Java 21** with **Gradle Kotlin DSL**. Java 21 is the current LTS and provides virtual threads (Project Loom, `Executors.newVirtualThreadPerTaskExecutor()`), sealed interfaces, and records — all used in this project. Zero runtime dependencies; test-only deps are JUnit 5 + AssertJ.

## Architecture overview

```
Downloader (AutoCloseable)
├── HEAD preflight        → detect server capabilities
├── RangePlanner          → totalBytes + chunkSize → List<ByteRange>
├── FileAssembler         → temp-file lifecycle (open/write/fsync/atomic-move)
│   └── ChunkSink         → positional FileChannel.write (thread-safe per FileChannel spec)
├── JdkHttpAdapter        → java.net.http.HttpClient (HEAD + ranged GET)
│   └── FakeHttpAdapter   → in-memory test double (test sources only)
└── RetryPolicy           → attempt + Trigger → Optional<Duration>
```

## Parallel-download strategy

1. **HEAD preflight**: check `Accept-Ranges: bytes` and `Content-Length`.
2. **Probe chunk 0 first**: send the first range request; if the server returns `200` (not `206`), the full body is already in the temp file — commit and report `chunks=1`. This prevents corruption when a server advertises range support in `HEAD` but ignores `Range` in `GET`.
3. **Parallel chunks 1..N**: submitted to a virtual-thread-per-task executor; bounded by `parallelism` option. Each chunk writes directly to its byte offset in the temp file using positional `FileChannel.write`, which the JDK specifies as safe for concurrent writes at non-overlapping positions.
4. **Atomic commit**: `FileChannel.force(true)` (fsync) followed by `Files.move(..., ATOMIC_MOVE)`. If any step fails, `abort()` deletes the `.part` file; the destination is never touched.

## The 200-vs-206 hazard

Some servers advertise `Accept-Ranges: bytes` in a `HEAD` response but return `200` with the full body when they receive a `GET` with a `Range` header. If we blindly launch all chunks in parallel and they all get `200` + full body, each chunk would write the full content starting at its designated offset — corrupting the file.

**Our mitigation**: probe chunk 0 synchronously before launching other chunks. If it gets `200`, we treat the body it already wrote (from offset 0) as the complete file, skip the parallel phase, and commit. Other chunks never run, so there is no window for corruption.

A future improvement is `If-Range` (see below).

## Retry policy

Retryable: `408`, `429`, `500`, `502`, `503`, `504`; `IOException`; connect/read timeout.
Not retried: all other `4xx` (auth failures, not-found, etc.).

Delay formula: `random(0, min(30s, baseDelay × 2^attempt))` — full jitter prevents thundering-herd. If the server provides `Retry-After` on `429`/`503`, that duration is used directly.

## Tuning rationale for defaults

| Option | Default | Rationale |
|---|---|---|
| `chunkSize = 8 MiB` | Amortises TCP slow-start and HTTP overhead; 8 chunks of 8 MiB covers a 64 MiB file cleanly |
| `parallelism = 8` | Empirical sweet spot for a single CDN host on a 1 Gbps link without overwhelming the server |
| `connectTimeout = 10 s` | Long enough for geo-distributed CDN but short enough to detect dead hosts |
| `requestTimeout = 60 s` | Covers a full 8 MiB chunk at ~1 Mbps |
| `maxRetriesPerChunk = 3` | Handles transient CDN blips without waiting forever |
| `retryBaseDelay = 200 ms` | At attempt 2, expected wait ≈ 400 ms; acceptable for interactive use |

## Out of scope

- **`If-Range` / ETag resumption**: after a retry mid-chunk, we re-download the whole chunk from its start offset. A proper `If-Range` + `ETag` implementation would resume from the exact byte position, but adds significant state management and is rarely needed for chunk sizes ≤ 16 MiB.
- **Checksum validation against a manifest**: the library verifies `Content-Length` but does not fetch or validate an external SHA-256 manifest. Callers can verify the result path themselves.
- **Bandwidth throttling / rate limiting**: not implemented; callers can reduce `parallelism` and `chunkSize` to limit throughput.
- **HTTP/2 multiplexing**: `java.net.http.HttpClient` uses HTTP/2 when the server supports it. We don't explicitly disable it, but we also don't tune for it.
- **Progress callbacks**: the public API returns a completed `DownloadResult`; streaming progress events would require a callback interface or reactive streams.
