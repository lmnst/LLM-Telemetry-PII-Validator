package com.example.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Pluggable HTTP transport used by {@link Downloader}. The default
 * implementation wraps {@code java.net.http.HttpClient}; tests inject a
 * fake adapter to exercise edge cases (truncated bodies, malformed
 * {@code Content-Range}, mid-body socket resets) without standing up a real
 * server. Implementations need not be thread-safe at the request level ,
 * the downloader serialises HEAD before issuing concurrent GETs, and each
 * GET is independent.
 */
public interface HttpAdapter {

    /**
     * Issues an HTTP HEAD request.
     *
     * @param uri the target URI
     * @return the HEAD response (status, length, range support, validators)
     * @throws IOException          on network or transport failure
     * @throws InterruptedException if cancelled
     */
    HeadResponse head(URI uri) throws IOException, InterruptedException;

    /**
     * Executes a GET for the given range and streams the response body to
     * {@code sink} in bounded buffers. Returns the HTTP status, actual bytes
     * written, the {@code Content-Range} header value (if any), and an
     * {@code ifRangeMismatch} flag set when {@code ifRange} was non-null AND
     * the server returned a {@code 200} (the validator did not match, the
     * resource has changed).
     *
     * @param range   {@code null} to send no {@code Range} header (single-stream)
     * @param ifRange {@code null} to send no {@code If-Range} header; otherwise
     *                the validator value (an ETag or HTTP-date)
     * @param uri     the target URI
     * @param sink    receives buffers of body bytes; called multiple times
     * @param cancel  cooperative cancellation token; the adapter should
     *                check between reads and abort with
     *                {@link InterruptedException} when set
     * @return        the response shape
     * @throws IOException          on network or transport failure
     * @throws InterruptedException if cancelled mid-body
     */
    GetResponse get(URI uri, ByteRange range, String ifRange,
                    Consumer<ByteBuffer> sink, CancelToken cancel)
            throws IOException, InterruptedException;

    /**
     * The shape of an HTTP HEAD response that the downloader cares about.
     *
     * @param status         HTTP status code
     * @param contentLength  body length in bytes; {@code -1} if unknown
     * @param acceptRanges   true iff {@code Accept-Ranges: bytes} was set
     * @param etag           value of the {@code ETag} header, or null
     * @param lastModified   value of the {@code Last-Modified} header, or null
     */
    record HeadResponse(int status, long contentLength, boolean acceptRanges,
                        String etag, String lastModified) {}

    /**
     * The shape of an HTTP GET response that the downloader cares about.
     *
     * @param status              HTTP status code
     * @param bytesWritten        actual bytes the adapter handed to the sink
     * @param contentRangeHeader  value of the {@code Content-Range} header, or null
     * @param ifRangeMismatch     true iff {@code ifRange} was sent AND the
     *                            server returned 200 (validator no longer matches)
     * @param retryAfter          parsed {@code Retry-After} hint when present
     *                            (non-negative, clamped at 5 minutes); empty
     *                            when the header was absent or unparseable
     */
    record GetResponse(int status, long bytesWritten, String contentRangeHeader,
                       boolean ifRangeMismatch, Optional<Duration> retryAfter) {

        /**
         * Convenience constructor for adapters that never surface a
         * {@code Retry-After} hint.
         *
         * @param status              HTTP status code
         * @param bytesWritten        bytes the adapter handed to the sink
         * @param contentRangeHeader  value of the {@code Content-Range} header, or null
         * @param ifRangeMismatch     true iff a stale {@code If-Range} was sent
         */
        public GetResponse(int status, long bytesWritten, String contentRangeHeader,
                           boolean ifRangeMismatch) {
            this(status, bytesWritten, contentRangeHeader, ifRangeMismatch, Optional.empty());
        }
    }
}
