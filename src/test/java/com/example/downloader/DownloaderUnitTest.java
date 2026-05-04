package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class DownloaderUnitTest {

    private static final URI FAKE_URI = URI.create("http://fake.example.com/file");

    @TempDir Path tmp;

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private Downloader downloaderWith(HttpAdapter adapter) {
        return new Downloader(DownloaderOptions.defaults(), adapter);
    }

    // ── parallel (range-capable server) ─────────────────────────────────────

    @Test
    void parallelDownload_sha256Matches() throws Exception {
        byte[] data = randomBytes(4 * 1024 * 1024, 1L); // 4 MiB
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    @Test
    void parallelDownload_reportedBytesAndChunksAreCorrect() throws Exception {
        byte[] data = randomBytes(8 * 1024 * 1024 + 100, 2L); // not an exact multiple
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(1024 * 1024L) // 1 MiB chunks → 9 chunks
                .parallelism(4)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            DownloadResult.Success s = (DownloadResult.Success) result;
            assertThat(s.bytes()).isEqualTo(data.length);
            assertThat(s.chunks()).isEqualTo(9);
            assertThat(s.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    // ── fallback: server returns 200 instead of 206 ──────────────────────────

    @Test
    void fallbackToSingleStream_whenServerReturns200OnRangeRequest() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 3L);
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(true)  // HEAD says yes …
                .forceFullGet(true)  // … but GET always returns 200
                .build();

        try (Downloader dl = downloaderWith(fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── single-stream mode (no Accept-Ranges) ────────────────────────────────

    @Test
    void singleStreamDownload_noRangeSupport() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 4L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.noRangeSupport(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    @Test
    void singleStreamDownload_parallelism1ForcesStream() throws Exception {
        byte[] data = randomBytes(512 * 1024, 5L);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder().parallelism(1).build();
        FakeHttpAdapter fake = FakeHttpAdapter.parallelCapable(data);

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── retry ────────────────────────────────────────────────────────────────

    @Test
    void retryOnIoFailure_succeedsAfterTransientError() throws Exception {
        byte[] data = randomBytes(1024, 6L);
        Path dest = tmp.resolve("out.bin");

        // Fail GET call 0, succeed on call 1
        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(false)
                .failGetOnAttempt(0, new IOException("connection reset"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .maxRetriesPerChunk(3)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    @Test
    void noRetry_returnsFailureAfterMaxRetries() throws Exception {
        byte[] data = randomBytes(1024, 7L);
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(false)
                .failGetOnAttempt(0, new IOException("always fails"))
                .failGetOnAttempt(1, new IOException("always fails"))
                .failGetOnAttempt(2, new IOException("always fails"))
                .failGetOnAttempt(3, new IOException("always fails"))
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .maxRetriesPerChunk(2)
                .retryBaseDelay(Duration.ZERO)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.IO_ERROR);
        }

        assertThat(dest).doesNotExist();
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    @Test
    void cancellation_leavesNoPartialFile() throws Exception {
        byte[] data = randomBytes(4 * 1024 * 1024, 8L);
        Path dest = tmp.resolve("out.bin");

        // Use downloadAsync, cancel immediately
        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadHandle handle = dl.downloadAsync(FAKE_URI, dest);
            handle.cancel();
            DownloadResult result = handle.join();
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
        }

        assertThat(dest).doesNotExist();
    }

    // ── small files ──────────────────────────────────────────────────────────

    @Test
    void tinyFile_singleByte() throws Exception {
        byte[] data = new byte[]{42};
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    @Test
    void exactlyOneChunk() throws Exception {
        byte[] data = randomBytes(8 * 1024 * 1024, 9L); // exactly one default chunk
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            assertThat(((DownloadResult.Success) result).chunks()).isEqualTo(1);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── async ────────────────────────────────────────────────────────────────

    @Test
    void downloadAsync_returnsSuccess() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 10L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = downloaderWith(FakeHttpAdapter.parallelCapable(data))) {
            DownloadHandle handle = dl.downloadAsync(FAKE_URI, dest);
            DownloadResult result = handle.join();
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            assertThat(handle.state()).isEqualTo(DownloadHandle.State.DONE);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(sha256(data));
    }

    // ── HEAD failures ────────────────────────────────────────────────────────

    @Test
    void headReturns404_returnsHttpError() throws Exception {
        Path dest = tmp.resolve("out.bin");

        HttpAdapter failHead = new HttpAdapter() {
            @Override public HeadResponse head(java.net.URI uri) {
                return new HeadResponse(404, -1, false, null);
            }
            @Override public GetResponse get(java.net.URI uri, ByteRange range,
                    java.util.function.Consumer<java.nio.ByteBuffer> sink, CancelToken cancel) {
                throw new UnsupportedOperationException();
            }
        };

        try (Downloader dl = downloaderWith(failHead)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error()).isEqualTo(DownloadError.HTTP_ERROR);
        }

        assertThat(dest).doesNotExist();
    }
}
