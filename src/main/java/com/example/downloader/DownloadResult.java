package com.example.downloader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public sealed interface DownloadResult {

    record Success(Path file, long bytes, Duration elapsed, int chunks, Optional<byte[]> sha256)
            implements DownloadResult {
        public Success {
            if (sha256 == null) sha256 = Optional.empty();
        }
    }

    record Failure(DownloadError error, Throwable cause) implements DownloadResult {}
}
