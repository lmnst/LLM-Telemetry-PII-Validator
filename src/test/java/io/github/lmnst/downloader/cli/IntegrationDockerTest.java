package io.github.lmnst.downloader.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test that drives the CLI against an httpd:2.4 container hosting a
 * generated test corpus. The destination file's SHA-256 must equal the corpus's.
 *
 * Skipped automatically when Docker is unavailable. Override the corpus size with
 * `-Ddownloader.it.size=<bytes>`; default is 32 MiB.
 */
@Tag("integration")
class IntegrationDockerTest {

    private static final long FILE_SIZE = sizeFromSystemProperty();
    private static final long SEED = 42L;
    private static final String DOC_ROOT_PATH = "/usr/local/apache2/htdocs/test.bin";

    private static GenericContainer<?> httpd;
    private static Path corpusFile;
    private static byte[] expectedSha256;

    @BeforeAll
    static void startContainer(@TempDir Path corpusDir)
            throws IOException, NoSuchAlgorithmException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available; skipping IntegrationDockerTest");

        corpusFile = corpusDir.resolve("test.bin");
        expectedSha256 = generateCorpus(corpusFile, FILE_SIZE, SEED);

        httpd = new GenericContainer<>("httpd:2.4")
                .withCopyFileToContainer(MountableFile.forHostPath(corpusFile), DOC_ROOT_PATH)
                .withExposedPorts(80)
                .waitingFor(Wait.forListeningPort());
        httpd.start();
    }

    @AfterAll
    static void stopContainer() {
        if (httpd != null) httpd.stop();
    }

    @Test
    void cliDownload_sha256Matches(@TempDir Path workDir) throws Exception {
        String url = "http://" + httpd.getHost() + ":" + httpd.getMappedPort(80) + "/test.bin";
        Path dest = workDir.resolve("downloaded.bin");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int code = Main.run(new String[]{
                "--url", url,
                "--out", dest.toString(),
                "--chunk-size", "4M",
                "--parallelism", "4",
                "--report", "json"
        }, new PrintStream(stdout), new PrintStream(stderr));

        assertThat(code)
                .withFailMessage("CLI failed. stdout=%s stderr=%s",
                        stdout.toString(), stderr.toString())
                .isEqualTo(0);

        String json = stdout.toString();
        assertThat(json).contains("\"status\":\"success\"");
        assertThat(json).contains("\"bytes\":" + FILE_SIZE);

        assertThat(Files.size(dest)).isEqualTo(FILE_SIZE);
        assertThat(sha256(dest)).isEqualTo(expectedSha256);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static byte[] generateCorpus(Path file, long size, long seed)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (OutputStream raw = Files.newOutputStream(file);
             DigestOutputStream out = new DigestOutputStream(raw, md)) {
            byte[] buf = new byte[64 * 1024];
            Random rng = new Random(seed);
            long remaining = size;
            while (remaining > 0) {
                int n = (int) Math.min(buf.length, remaining);
                rng.nextBytes(buf);
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
        return md.digest();
    }

    private static byte[] sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return md.digest();
    }

    private static long sizeFromSystemProperty() {
        String v = System.getProperty("downloader.it.size");
        return v == null ? 32L * 1024 * 1024 : Long.parseLong(v);
    }
}
