package io.github.lmnst.downloader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Outcome of a {@link Downloader#download} call. Sealed: every result is
 * exactly one of {@link Success} or {@link Failure}; callers should
 * pattern-match exhaustively.
 */
public sealed interface DownloadResult {

    /**
     * Successful download. The destination file exists at {@code file} and
     * has been atomically committed.
     *
     * @param file    the destination path
     * @param bytes   bytes written
     * @param elapsed wall-clock time for the entire operation (HEAD + GETs + verify + commit)
     * @param chunks  chunk count actually used
     * @param sha256  computed digest if {@code expectedDigest} was configured;
     *                empty otherwise
     */
    record Success(Path file, long bytes, Duration elapsed, int chunks, Optional<byte[]> sha256)
            implements DownloadResult {

        /** Normalises a null {@code sha256} component to {@link Optional#empty()}. */
        public Success {
            if (sha256 == null) sha256 = Optional.empty();
        }
    }

    /**
     * Failed download. The destination file does not exist; in FRESH mode
     * any {@code .part} / {@code .part.meta} artifacts have been deleted
     * (RESUME_IF_VALID preserves them).
     *
     * @param error typed error category
     * @param cause underlying exception, or null
     */
    record Failure(DownloadError error, Throwable cause) implements DownloadResult {}
}
