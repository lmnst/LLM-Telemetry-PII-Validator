package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrityTest {

    private static final URI URI_ = URI.create("http://fake.example.com/file");

    @TempDir Path tmp;

    @Test
    void matchedDigest_succeedsWithSha256Populated() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 1L);
        byte[] expected = sha256(data);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .expectedDigest(Algorithm.SHA_256, expected)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(URI_, dest);
            assertThat(result).isInstanceOf(DownloadResult.Success.class);
            DownloadResult.Success s = (DownloadResult.Success) result;
            assertThat(s.sha256()).isPresent();
            assertThat(s.sha256().get()).isEqualTo(expected);
        }
        assertThat(dest).exists();
    }

    @Test
    void mismatchedDigest_failsAndCleansUp_parallel() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 2L);
        byte[] tampered = sha256(new byte[]{1, 2, 3}); // different hash
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .expectedDigest(Algorithm.SHA_256, tampered)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(URI_, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            DownloadResult.Failure f = (DownloadResult.Failure) result;
            assertThat(f.error()).isEqualTo(DownloadError.INTEGRITY_FAILURE);
            assertThat(f.cause().getMessage()).contains("integrity check failed");
        }
        assertThat(dest).doesNotExist();
        assertNoPartFiles();
    }

    @Test
    void mismatchedDigest_failsAndCleansUp_singleStream() throws Exception {
        byte[] data = randomBytes(8 * 1024, 3L);
        byte[] tampered = sha256(new byte[]{99});
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .parallelism(1)
                .expectedDigest(Algorithm.SHA_256, tampered)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(URI_, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error())
                    .isEqualTo(DownloadError.INTEGRITY_FAILURE);
        }
        assertThat(dest).doesNotExist();
        assertNoPartFiles();
    }

    @Test
    void mismatchedDigest_failsAndCleansUp_probe200Fallback() throws Exception {
        byte[] data = randomBytes(8 * 1024, 4L);
        byte[] tampered = sha256(new byte[]{77});
        Path dest = tmp.resolve("out.bin");

        FakeHttpAdapter fake = FakeHttpAdapter.builder(data)
                .acceptRanges(true)
                .forceFullGet(true)
                .build();

        DownloaderOptions opts = DownloaderOptions.builder()
                .expectedDigest(Algorithm.SHA_256, tampered)
                .build();

        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(URI_, dest);
            assertThat(result).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) result).error())
                    .isEqualTo(DownloadError.INTEGRITY_FAILURE);
        }
        assertThat(dest).doesNotExist();
        assertNoPartFiles();
    }

    @Test
    void noExpectedDigest_resultSha256IsEmpty() throws Exception {
        byte[] data = randomBytes(1024, 5L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = new Downloader(DownloaderOptions.defaults(),
                FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(URI_, dest);
            DownloadResult.Success s = (DownloadResult.Success) result;
            assertThat(s.sha256()).isEmpty();
        }
    }

    /** Locks the digest pipeline against a published SHA-256 reference vector. */
    @Test
    void precomputedReference_sha256OfAsciiABC() throws Exception {
        byte[] data = "ABC".getBytes();
        String referenceHex =
                "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78";
        byte[] expected = HexFormat.of().parseHex(referenceHex);
        Path dest = tmp.resolve("out.bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .expectedDigest(Algorithm.SHA_256, expected)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult result = dl.download(URI_, dest);
            DownloadResult.Success s = (DownloadResult.Success) result;
            assertThat(HexFormat.of().formatHex(s.sha256().orElseThrow()))
                    .isEqualTo(referenceHex);
        }
    }

    @Test
    void expectedDigest_wrongLength_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ExpectedDigest(Algorithm.SHA_256, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private void assertNoPartFiles() throws Exception {
        try (var stream = Files.list(tmp)) {
            assertThat(stream.noneMatch(p -> p.getFileName().toString().endsWith(".part")))
                    .as("no .part file should remain in tmp dir")
                    .isTrue();
        }
    }
}
