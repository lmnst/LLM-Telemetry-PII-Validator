package io.github.lmnst.downloader;

import java.io.IOException;

/**
 * The underlying cause for {@link DownloadResult.Failure}s with
 * {@link DownloadError#HTTP_ERROR}. Carries the HTTP status code so callers
 * (e.g. the CLI's exit-code mapper) can split 4xx and 5xx without parsing
 * exception messages.
 */
public final class HttpStatusException extends IOException {

    /**
     * HTTP status code observed on the failed request.
     * @serial
     */
    private final int statusCode;

    /**
     * Constructs an exception carrying the given HTTP status code.
     *
     * @param statusCode the HTTP status code observed on the failed request
     */
    public HttpStatusException(int statusCode) {
        super("HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    /** {@return the HTTP status code that produced this failure} */
    public int statusCode() {
        return statusCode;
    }
}
