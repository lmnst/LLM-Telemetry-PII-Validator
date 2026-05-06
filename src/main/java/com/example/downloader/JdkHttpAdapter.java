package com.example.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Consumer;

final class JdkHttpAdapter implements HttpAdapter, AutoCloseable {

    private static final int READ_BUFFER_SIZE = 64 * 1024;
    private static final Duration RETRY_AFTER_CAP = Duration.ofMinutes(5);

    private final HttpClient client;
    private final Duration requestTimeout;
    private final String userAgent;

    public JdkHttpAdapter(DownloaderOptions options) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .build();
        this.requestTimeout = options.requestTimeout();
        this.userAgent = options.userAgent();
    }

    @Override
    public HeadResponse head(URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", userAgent)
                .timeout(requestTimeout)
                .build();

        // HttpTimeoutException already extends IOException, so we let it
        // propagate unwrapped; Downloader pattern-matches on it to surface
        // DownloadError.TIMEOUT distinctly from generic IO failures.
        HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());

        long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        boolean acceptRanges = resp.headers().firstValue("Accept-Ranges")
                .map(v -> v.equalsIgnoreCase("bytes"))
                .orElse(false);
        String etag = resp.headers().firstValue("ETag").orElse(null);
        String lastModified = resp.headers().firstValue("Last-Modified").orElse(null);

        return new HeadResponse(resp.statusCode(), contentLength, acceptRanges, etag, lastModified);
    }

    @Override
    public GetResponse get(URI uri, ByteRange range, String ifRange,
                           Consumer<ByteBuffer> sink, CancelToken cancel)
            throws IOException, InterruptedException {

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
                .GET()
                .header("User-Agent", userAgent)
                .timeout(requestTimeout);

        if (range != null) {
            reqBuilder.header("Range", range.httpHeaderValue());
        }
        if (ifRange != null) {
            reqBuilder.header("If-Range", ifRange);
        }

        // Same propagation as head(): HttpTimeoutException flows through as
        // an IOException subtype; the Downloader maps it onto Trigger.Timeout.
        HttpResponse<InputStream> resp =
                client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

        String contentRange = resp.headers().firstValue("Content-Range").orElse(null);
        long bytesWritten = 0;
        byte[] buf = new byte[READ_BUFFER_SIZE];

        try (InputStream body = resp.body()) {
            int n;
            while ((n = body.read(buf)) != -1) {
                if (cancel.isCancelled()) throw new InterruptedException("cancelled during GET body");
                sink.accept(ByteBuffer.wrap(buf, 0, n));
                bytesWritten += n;
            }
        }

        boolean ifRangeMismatch = ifRange != null && resp.statusCode() == 200;
        Optional<Duration> retryAfter = parseRetryAfter(
                resp.headers().firstValue("Retry-After").orElse(null));
        return new GetResponse(resp.statusCode(), bytesWritten, contentRange,
                ifRangeMismatch, retryAfter);
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * Parses an RFC 9110 {@code Retry-After} header value. Accepts both
     * delta-seconds (a non-negative integer) and HTTP-date forms. The result
     * is clamped to {@code [0, 5 minutes]} so a hostile or fat-fingered server
     * cannot stall the client for an unbounded period. Returns {@link
     * Optional#empty()} if the header is null, blank, malformed, or describes
     * a time in the past.
     */
    static Optional<Duration> parseRetryAfter(String header) {
        if (header == null) return Optional.empty();
        String s = header.trim();
        if (s.isEmpty()) return Optional.empty();

        Duration parsed;
        try {
            long seconds = Long.parseLong(s);
            if (seconds < 0) return Optional.empty();
            parsed = Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                ZonedDateTime when = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
                parsed = Duration.between(ZonedDateTime.now(when.getZone()), when);
            } catch (DateTimeParseException unparseable) {
                return Optional.empty();
            }
        }

        if (parsed.isNegative()) parsed = Duration.ZERO;
        if (parsed.compareTo(RETRY_AFTER_CAP) > 0) parsed = RETRY_AFTER_CAP;
        return Optional.of(parsed);
    }
}
