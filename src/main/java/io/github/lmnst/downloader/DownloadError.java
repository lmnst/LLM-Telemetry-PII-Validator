package io.github.lmnst.downloader;

/**
 * Categories of download failure surfaced by {@link DownloadResult.Failure}.
 * Every failure path in the downloader maps to exactly one of these; callers
 * can {@code switch} on the value to drive retry policy or exit codes.
 */
public enum DownloadError {

    /** HEAD reported a server that does not advertise {@code Accept-Ranges: bytes}. */
    RANGES_NOT_SUPPORTED,

    /** Non-retryable HTTP status (e.g. 404, 401), or retries exhausted on a 5xx. */
    HTTP_ERROR,

    /** Local I/O or non-HTTP network failure. */
    IO_ERROR,

    /** The download was cancelled via {@link DownloadHandle#cancel()}. */
    CANCELLED,

    /** Sum of bytes received does not match the {@code Content-Length} from HEAD. */
    SIZE_MISMATCH,

    /** Computed SHA-256 did not match the expected digest configured on options. */
    INTEGRITY_FAILURE,

    /**
     * In {@code RESUME_IF_VALID} mode, a manifest validator (URL, ETag,
     * Content-Length, chunk size) no longer matches the server's HEAD or
     * an {@code If-Range} GET returned {@code 200}, the resource has changed.
     */
    RESOURCE_CHANGED,

    /** Connect or request timeout. */
    TIMEOUT
}
