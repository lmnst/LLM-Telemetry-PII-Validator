# Parallel Range-GET File Downloader

A Java 21 library that downloads large files in parallel using HTTP range requests, with automatic retry, cancellation, and atomic assembly.

## Requirements

- Java 21+
- Gradle 8.13+ (wrapper included — no separate install needed)

## Building & testing

```bash
./gradlew test          # compile + run all 106 tests
./gradlew test --info   # verbose output
```

## Usage

```java
import com.example.downloader.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

// 1. Build options (or use defaults)
DownloaderOptions opts = DownloaderOptions.builder()
        .chunkSize(8 * 1024 * 1024L)   // 8 MiB per chunk
        .parallelism(8)                 // up to 8 concurrent GETs
        .connectTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(60))
        .maxRetriesPerChunk(3)
        .retryBaseDelay(Duration.ofMillis(200))
        .build();

// 2. Synchronous download
try (Downloader downloader = new Downloader(opts)) {
    DownloadResult result = downloader.download(
            URI.create("https://example.com/large-file.bin"),
            Path.of("/tmp/large-file.bin")
    );

    switch (result) {
        case DownloadResult.Success s ->
            System.out.printf("Downloaded %d bytes in %s (%d chunks)%n",
                    s.bytes(), s.elapsed(), s.chunks());
        case DownloadResult.Failure f ->
            System.err.println("Failed: " + f.error() + " — " + f.cause());
    }
}

// 3. Async with cancellation
try (Downloader downloader = new Downloader(DownloaderOptions.defaults())) {
    DownloadHandle handle = downloader.downloadAsync(
            URI.create("https://example.com/huge.bin"),
            Path.of("/tmp/huge.bin")
    );

    // cancel after 5 seconds if not done
    Thread.sleep(5_000);
    if (handle.state() == DownloadHandle.State.RUNNING) handle.cancel();

    DownloadResult result = handle.join();
}
```

## Key behaviours

| Scenario | Behaviour |
|---|---|
| Server supports `Accept-Ranges: bytes` | Parallel range-GET with configurable chunk size and parallelism |
| Server ignores Range header (returns 200) | Falls back to single-stream; file is never corrupted |
| Transient errors (408/429/5xx, `IOException`, timeout) | Exponential backoff + full jitter; honours `Retry-After` |
| Non-retryable errors (400/401/403/404/…) | Immediate failure |
| Cancellation | In-flight requests aborted; temp file deleted; destination untouched |
| Any failure | `.part` temp file deleted; destination never written |

## Default options

| Option | Default | Notes |
|---|---|---|
| `chunkSize` | 8 MiB | Amortises TCP slow-start |
| `parallelism` | 8 | Sweet spot for single-host downloads |
| `connectTimeout` | 10 s | |
| `requestTimeout` | 60 s | Per-chunk including body transfer |
| `maxRetriesPerChunk` | 3 | |
| `retryBaseDelay` | 200 ms | Exponential + full jitter, capped at 30 s |
