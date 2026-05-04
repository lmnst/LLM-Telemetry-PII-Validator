package com.example.downloader;

import java.nio.file.Path;
import java.time.Duration;

public sealed interface DownloadResult {

    record Success(Path file, long bytes, Duration elapsed, int chunks) implements DownloadResult {}

    record Failure(DownloadError error, Throwable cause) implements DownloadResult {}
}
