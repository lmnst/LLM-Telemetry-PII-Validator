package com.example.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.Consumer;

final class JdkHttpAdapter implements HttpAdapter, AutoCloseable {

    private static final int READ_BUFFER_SIZE = 64 * 1024;

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

        HttpResponse<Void> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("HEAD timed out: " + uri, e);
        }

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

        HttpResponse<InputStream> resp;
        try {
            resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("GET timed out: " + uri, e);
        }

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
        return new GetResponse(resp.statusCode(), bytesWritten, contentRange, ifRangeMismatch);
    }

    @Override
    public void close() {
        client.close();
    }
}
