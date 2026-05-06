package io.github.lmnst.downloader;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Property-based test: random file sizes and chunk sizes, verifying SHA-256 integrity.
 * Uses FakeHttpAdapter to keep tests fast (no network I/O).
 */
class PropertyTest {

    private static final URI FAKE_URI = URI.create("http://prop.test/file");

    @TempDir Path tmp;

    @ParameterizedTest(name = "[{index}] size={0}, chunkSize={1}")
    @MethodSource("randomParams")
    void sha256MatchesForRandomSizeAndChunk(int fileSize, int chunkSizeBytes) throws Exception {
        byte[] data = new byte[fileSize];
        new Random(fileSize ^ ((long) chunkSizeBytes << 32)).nextBytes(data);
        byte[] expected = sha256(data);

        Path dest = tmp.resolve("out_" + fileSize + "_" + chunkSizeBytes + ".bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(chunkSizeBytes)
                .parallelism(4)
                .retryBaseDelay(Duration.ZERO)
                .build();

        FakeHttpAdapter fake = FakeHttpAdapter.parallelCapable(data);
        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result)
                    .as("size=%d chunk=%d", fileSize, chunkSizeBytes)
                    .isInstanceOf(DownloadResult.Success.class);
        }

        byte[] actual = sha256(Files.readAllBytes(dest));
        assertThat(actual)
                .as("SHA-256 mismatch for size=%d chunk=%d", fileSize, chunkSizeBytes)
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] size={0}, chunkSize={1} (no-range)")
    @MethodSource("smallRandomParams")
    void sha256MatchesNoRangeMode(int fileSize, int chunkSizeBytes) throws Exception {
        byte[] data = new byte[fileSize];
        new Random(fileSize + chunkSizeBytes).nextBytes(data);
        byte[] expected = sha256(data);

        Path dest = tmp.resolve("norange_" + fileSize + ".bin");

        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(chunkSizeBytes)
                .parallelism(4)
                .retryBaseDelay(Duration.ZERO)
                .build();

        FakeHttpAdapter fake = FakeHttpAdapter.noRangeSupport(data);
        try (Downloader dl = new Downloader(opts, fake)) {
            DownloadResult result = dl.download(FAKE_URI, dest);
            assertThat(result)
                    .as("no-range size=%d chunk=%d", fileSize, chunkSizeBytes)
                    .isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(sha256(Files.readAllBytes(dest))).isEqualTo(expected);
    }

    // ── parameter providers ───────────────────────────────────────────────────

    static Stream<Arguments> randomParams() {
        Random rng = new Random(0xDEADBEEF);
        return Stream.generate(() -> {
            int size      = rng.nextInt(10 * 1024 * 1024 + 1); // [0, 10 MiB]
            int chunkSize = 1024 + rng.nextInt(1024 * 1024);     // [1 KiB, ~1 MiB]
            return Arguments.of(size, chunkSize);
        }).limit(25);
    }

    static Stream<Arguments> smallRandomParams() {
        Random rng = new Random(0xCAFEBABE);
        return Stream.generate(() -> {
            int size      = rng.nextInt(512 * 1024 + 1);  // [0, 512 KiB]
            int chunkSize = 1024 + rng.nextInt(64 * 1024); // [1 KiB, 64 KiB]
            return Arguments.of(size, chunkSize);
        }).limit(15);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
