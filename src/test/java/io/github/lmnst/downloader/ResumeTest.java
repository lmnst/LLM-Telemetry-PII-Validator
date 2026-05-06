package io.github.lmnst.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTest {

    private static final URI URI_ = URI.create("http://fake.example.com/file");
    private static final long CHUNK_SIZE = 256 * 1024L; // 256 KiB → 8 chunks for 2 MiB

    @TempDir Path tmp;

    private DownloaderOptions resumeOpts() {
        return DownloaderOptions.builder()
                .chunkSize(CHUNK_SIZE)
                .parallelism(2)
                .maxRetriesPerChunk(0)
                .retryBaseDelay(Duration.ZERO)
                .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
                .build();
    }

    @Test
    void killMidDownload_resumeFetchesOnlyMissingChunks() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 1L);
        Path dest = tmp.resolve("out.bin");

        // Run 1: GETs above offset 4*chunkSize (= 1 MiB) fail. Chunks 0-3 succeed.
        FakeHttpAdapter run1 = FakeHttpAdapter.builder(data)
                .failGetsAtOrAboveOffset(4 * CHUNK_SIZE)
                .build();

        try (Downloader dl = new Downloader(resumeOpts(), run1)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Failure.class);
        }

        // Resume mode preserves both files on failure
        Path manifestPath = Manifest.pathFor(dest);
        Path partFile = dest.resolveSibling(dest.getFileName() + ".part");
        assertThat(manifestPath).exists();
        assertThat(partFile).exists();

        // Read manifest to learn which chunks are completed
        Manifest m1 = Manifest.read(manifestPath);
        int completedAfterRun1 = m1.completed.cardinality();
        int totalChunks = m1.totalChunks();
        assertThat(completedAfterRun1).isBetween(1, totalChunks - 1);

        // Run 2: fresh adapter that succeeds; should fetch only missing chunks
        FakeHttpAdapter run2 = FakeHttpAdapter.parallelCapable(data);
        try (Downloader dl = new Downloader(resumeOpts(), run2)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
        // Manifest deleted on success
        assertThat(manifestPath).doesNotExist();
        // Run 2 fetched exactly the chunks that were missing
        assertThat(run2.getCallCount()).isEqualTo(totalChunks - completedAfterRun1);
    }

    @Test
    void etagMismatch_failsResourceChanged_preservesPartialState() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 2L);
        Path dest = tmp.resolve("out.bin");

        // Run 1: etag "v1", fail above offset
        FakeHttpAdapter run1 = FakeHttpAdapter.builder(data)
                .etag("\"v1\"")
                .failGetsAtOrAboveOffset(4 * CHUNK_SIZE)
                .build();
        try (Downloader dl = new Downloader(resumeOpts(), run1)) {
            dl.download(URI_, dest);
        }
        assertThat(Manifest.pathFor(dest)).exists();

        // Run 2: etag "v2" (resource has changed), adapter serves new data too
        byte[] newData = randomBytes(2 * 1024 * 1024, 99L);
        FakeHttpAdapter run2 = FakeHttpAdapter.builder(newData)
                .etag("\"v2\"")
                .build();
        try (Downloader dl = new Downloader(resumeOpts(), run2)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Failure.class);
            assertThat(((DownloadResult.Failure) r).error())
                    .isEqualTo(DownloadError.RESOURCE_CHANGED);
        }
        // Adapter should not have been called for any GET, failure happens after HEAD
        assertThat(run2.getCallCount()).isZero();
        // Manifest preserved so caller can decide to delete and retry FRESH
        assertThat(Manifest.pathFor(dest)).exists();
    }

    @Test
    void chunkSizeMismatch_failsResourceChanged() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 3L);
        Path dest = tmp.resolve("out.bin");

        // Run 1: chunkSize = 256 KiB, fail above offset
        FakeHttpAdapter run1 = FakeHttpAdapter.builder(data)
                .failGetsAtOrAboveOffset(2 * CHUNK_SIZE)
                .build();
        try (Downloader dl = new Downloader(resumeOpts(), run1)) {
            dl.download(URI_, dest);
        }
        assertThat(Manifest.pathFor(dest)).exists();

        // Run 2: same etag/data but different chunkSize → manifest invalid
        DownloaderOptions differentChunkSize = DownloaderOptions.builder()
                .chunkSize(512 * 1024L) // double the original
                .parallelism(2)
                .maxRetriesPerChunk(0)
                .retryBaseDelay(Duration.ZERO)
                .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
                .build();
        try (Downloader dl = new Downloader(differentChunkSize,
                FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(((DownloadResult.Failure) r).error())
                    .isEqualTo(DownloadError.RESOURCE_CHANGED);
        }
    }

    @Test
    void freshMode_overwritesExistingManifestAndPart() throws Exception {
        byte[] data = randomBytes(1024 * 1024, 4L);
        Path dest = tmp.resolve("out.bin");

        // Run 1 in resume mode, fails midway
        FakeHttpAdapter run1 = FakeHttpAdapter.builder(data)
                .failGetsAtOrAboveOffset(2 * CHUNK_SIZE)
                .build();
        try (Downloader dl = new Downloader(resumeOpts(), run1)) {
            dl.download(URI_, dest);
        }
        assertThat(Manifest.pathFor(dest)).exists();

        // Run 2 in FRESH mode (default), should ignore existing files
        DownloaderOptions freshOpts = DownloaderOptions.builder()
                .chunkSize(CHUNK_SIZE)
                .parallelism(2)
                .build();
        FakeHttpAdapter run2 = FakeHttpAdapter.parallelCapable(data);
        try (Downloader dl = new Downloader(freshOpts, run2)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
        assertThat(Manifest.pathFor(dest)).doesNotExist();
        // Fresh mode fetched ALL chunks (didn't reuse anything from prior partial)
        int totalChunks = (int) ((data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        assertThat(run2.getCallCount()).isEqualTo(totalChunks);
    }

    @Test
    void successOnFirstTry_deletesManifest() throws Exception {
        byte[] data = randomBytes(512 * 1024, 5L);
        Path dest = tmp.resolve("out.bin");

        try (Downloader dl = new Downloader(resumeOpts(),
                FakeHttpAdapter.parallelCapable(data))) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Manifest.pathFor(dest)).doesNotExist();
        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    @Test
    void resumeWithMatchingState_writesCorrectFile() throws Exception {
        byte[] data = randomBytes(2 * 1024 * 1024, 6L);
        Path dest = tmp.resolve("out.bin");

        // Run 1: fail at offset >= chunkSize (only chunk 0 succeeds)
        FakeHttpAdapter run1 = FakeHttpAdapter.builder(data)
                .failGetsAtOrAboveOffset(CHUNK_SIZE)
                .build();
        try (Downloader dl = new Downloader(resumeOpts(), run1)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Failure.class);
        }

        Manifest m1 = Manifest.read(Manifest.pathFor(dest));
        assertThat(m1.isCompleted(0)).isTrue();

        // Run 2: succeeds; verify final file matches
        FakeHttpAdapter run2 = FakeHttpAdapter.parallelCapable(data);
        try (Downloader dl = new Downloader(resumeOpts(), run2)) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        // SHA-256 should match; resume rewrote only missing bytes, leaving completed
        // chunks from run 1 untouched in the temp file.
        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(data);
        byte[] actualHash = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(dest));
        assertThat(actualHash).isEqualTo(expectedHash);
    }

    @Test
    void singleStreamMode_resumeIgnored() throws Exception {
        byte[] data = randomBytes(64 * 1024, 7L);
        Path dest = tmp.resolve("out.bin");

        // Server doesn't support ranges → single-stream mode → resume ignored
        DownloaderOptions opts = DownloaderOptions.builder()
                .resumeStrategy(ResumeStrategy.RESUME_IF_VALID)
                .build();

        try (Downloader dl = new Downloader(opts, FakeHttpAdapter.noRangeSupport(data))) {
            DownloadResult r = dl.download(URI_, dest);
            assertThat(r).isInstanceOf(DownloadResult.Success.class);
        }

        assertThat(Manifest.pathFor(dest)).doesNotExist();
        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
