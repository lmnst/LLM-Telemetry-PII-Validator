package com.example.downloader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests using an in-process HTTP server.
 * No Docker required. Uses the real JdkHttpAdapter + HttpClient stack.
 * For the Docker-backed counterpart see IntegrationDockerTest (@Tag("integration")).
 */
class IntegrationInProcessTest {

    private static final int FILE_SIZE = 4 * 1024 * 1024; // 4 MiB
    private static final byte[] FILE_DATA;
    private static final byte[] FILE_HASH;

    static {
        FILE_DATA = new byte[FILE_SIZE];
        new Random(42).nextBytes(FILE_DATA);
        try {
            FILE_HASH = MessageDigest.getInstance("SHA-256").digest(FILE_DATA);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private HttpServer server;
    private URI fileUri;
    private AtomicInteger getRangeCount;

    @TempDir Path tmp;

    // ── server setup ─────────────────────────────────────────────────────────

    @BeforeEach
    void startServer() throws IOException {
        getRangeCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private URI addRangeCapableContext(String path, byte[] data) {
        server.createContext(path, exchange -> serveWithRanges(exchange, data));
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private URI addNoRangeContext(String path, byte[] data) {
        server.createContext(path, exchange -> serveFullBody(exchange, data));
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void serveWithRanges(HttpExchange ex, byte[] data) throws IOException {
        String method = ex.getRequestMethod();
        if ("HEAD".equalsIgnoreCase(method)) {
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
            ex.getResponseHeaders().set("ETag", "\"test-etag\"");
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }

        String rangeHeader = ex.getRequestHeaders().getFirst("Range");
        if (rangeHeader == null) {
            serveFullBody(ex, data);
            return;
        }

        getRangeCount.incrementAndGet();
        int[] bounds = parseRange(rangeHeader, data.length);
        int from = bounds[0], to = bounds[1];
        int len = to - from + 1;

        ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
        ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
        ex.sendResponseHeaders(206, len);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data, from, len);
        }
        ex.close();
    }

    private void serveFullBody(HttpExchange ex, byte[] data) throws IOException {
        String method = ex.getRequestMethod();
        if ("HEAD".equalsIgnoreCase(method)) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
        ex.close();
    }

    private static int[] parseRange(String header, int total) {
        Matcher m = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(header);
        if (!m.find()) return new int[]{0, total - 1};
        return new int[]{Integer.parseInt(m.group(1)),
                         Math.min(Integer.parseInt(m.group(2)), total - 1)};
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void parallelDownload_sha256Matches() throws Exception {
        URI uri = addRangeCapableContext("/file", FILE_DATA);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(512 * 1024L)   // 512 KiB → 8 chunks for 4 MiB
                .parallelism(4)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try (Downloader dl = new Downloader(opts)) {
            DownloadResult result = dl.download(uri, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            DownloadResult.Success s = (DownloadResult.Success) result;
            assertThat(s.bytes()).isEqualTo(FILE_SIZE);
            assertThat(s.chunks()).isEqualTo(8);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(FILE_HASH);
        assertThat(getRangeCount.get()).isEqualTo(8);
    }

    @Test
    void singleStream_noRangeServer() throws Exception {
        URI uri = addNoRangeContext("/stream", FILE_DATA);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = new Downloader(DownloaderOptions.defaults())) {
            DownloadResult result = dl.download(uri, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(FILE_HASH);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1023, 1024, 8388608}) // edge-case sizes
    void variousFileSizes_sha256Matches(int size) throws Exception {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        byte[] expected = sha256(data);

        URI uri = addRangeCapableContext("/var", data);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(256 * 1024L)
                .parallelism(4)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try (Downloader dl = new Downloader(opts)) {
            DownloadResult result = dl.download(uri, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(expected);
    }

    @Test
    void serverReturns503ThenSucceeds_retryWorks() throws Exception {
        AtomicInteger getCount = new AtomicInteger();
        Path dest = tmp.resolve("out.bin");

        server.createContext("/retry", ex -> {
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(FILE_DATA.length));
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            int call = getCount.getAndIncrement();
            if (call == 0) {
                // first GET → 503
                ex.sendResponseHeaders(503, -1);
                ex.close();
            } else {
                serveFullBody(ex, FILE_DATA);
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/retry");

        DownloaderOptions opts = DownloaderOptions.builder()
                .parallelism(1)
                .maxRetriesPerChunk(3)
                .retryBaseDelay(Duration.ZERO)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(10))
                .build();

        try (Downloader dl = new Downloader(opts)) {
            DownloadResult result = dl.download(uri, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(FILE_HASH);
        assertThat(getCount.get()).isEqualTo(2); // 1 fail + 1 success
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
