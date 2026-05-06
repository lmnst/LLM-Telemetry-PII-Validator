package io.github.lmnst.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestTest {

    @TempDir Path tmp;

    @Test
    void roundtrip_preservesAllFieldsAndBitmap() throws Exception {
        URI url = URI.create("https://example.com/big.bin");
        HttpAdapter.HeadResponse head = new HttpAdapter.HeadResponse(
                200, 8 * 1024L * 1024L, true, "\"abc123\"",
                "Wed, 21 Oct 2015 07:28:00 GMT");

        Manifest m = Manifest.forNewDownload(url, head, 1024 * 1024L);
        m.markComplete(0);
        m.markComplete(2);
        m.markComplete(7);

        Path p = tmp.resolve("dest.bin.part.meta");
        m.writeAtomically(p);

        Manifest reread = Manifest.read(p);
        assertThat(reread.url).isEqualTo(url);
        assertThat(reread.etag).isEqualTo("\"abc123\"");
        assertThat(reread.lastModified).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        assertThat(reread.contentLength).isEqualTo(8 * 1024L * 1024L);
        assertThat(reread.chunkSize).isEqualTo(1024 * 1024L);
        assertThat(reread.isCompleted(0)).isTrue();
        assertThat(reread.isCompleted(1)).isFalse();
        assertThat(reread.isCompleted(2)).isTrue();
        assertThat(reread.isCompleted(7)).isTrue();
    }

    @Test
    void roundtrip_handlesNullEtagAndLastModified() throws Exception {
        URI url = URI.create("https://example.com/x");
        HttpAdapter.HeadResponse head = new HttpAdapter.HeadResponse(
                200, 1024L, true, null, null);
        Manifest m = Manifest.forNewDownload(url, head, 256L);

        Path p = tmp.resolve("x.part.meta");
        m.writeAtomically(p);
        Manifest reread = Manifest.read(p);

        assertThat(reread.etag).isNull();
        assertThat(reread.lastModified).isNull();
    }

    @Test
    void roundtrip_handlesUrlWithReservedAndNonAsciiCharacters() throws Exception {
        // A literal newline or '=' inside a value would have broken a naive
        // parser; URL-encoding lets these round-trip cleanly.
        URI url = URI.create("https://example.com/path%20with%20spaces/%E4%B8%AD?q=a%26b%3Dc");
        HttpAdapter.HeadResponse head = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"weird=etag\"\n with newline", null);
        Manifest m = Manifest.forNewDownload(url, head, 256L);

        Path p = tmp.resolve("weird.part.meta");
        m.writeAtomically(p);
        Manifest reread = Manifest.read(p);

        assertThat(reread.url).isEqualTo(url);
        assertThat(reread.etag).isEqualTo("\"weird=etag\"\n with newline");
    }

    @Test
    void atomicWrite_doesNotLeaveTmpFile() throws Exception {
        URI url = URI.create("https://example.com/x");
        HttpAdapter.HeadResponse head = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"e\"", null);
        Manifest m = Manifest.forNewDownload(url, head, 256L);

        Path p = tmp.resolve("x.part.meta");
        m.writeAtomically(p);

        try (var stream = Files.list(tmp)) {
            assertThat(stream.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")))
                    .as("no .tmp file should remain after atomic write")
                    .isTrue();
        }
    }

    @Test
    void onDisk_format_isHumanReadableKeyEqualsValue() throws Exception {
        URI url = URI.create("https://example.com/file");
        HttpAdapter.HeadResponse head = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"e1\"", null);
        Manifest m = Manifest.forNewDownload(url, head, 256L);
        m.markComplete(0);

        Path p = tmp.resolve("inspect.part.meta");
        m.writeAtomically(p);

        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertThat(text).startsWith("version=" + Manifest.CURRENT_VERSION);
        assertThat(text).contains("contentLength=1024");
        assertThat(text).contains("chunkSize=256");
        assertThat(text).contains("completed=01");
    }

    @Test
    void matchesHead_etagAndContentLengthAndChunkSize() {
        URI url = URI.create("https://example.com/x");
        HttpAdapter.HeadResponse h1 = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"v1\"", null);
        Manifest m = Manifest.forNewDownload(url, h1, 256L);

        assertThat(m.matchesHead(h1, 256L)).isTrue();

        HttpAdapter.HeadResponse h2 = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"v2\"", null);
        assertThat(m.matchesHead(h2, 256L)).isFalse();

        HttpAdapter.HeadResponse h3 = new HttpAdapter.HeadResponse(
                200, 2048L, true, "\"v1\"", null);
        assertThat(m.matchesHead(h3, 256L)).isFalse();

        assertThat(m.matchesHead(h1, 512L)).isFalse();
    }

    @Test
    void matchesHead_fallsBackToLastModifiedWhenEtagMissing() {
        URI url = URI.create("https://example.com/x");
        String lm = "Wed, 21 Oct 2015 07:28:00 GMT";
        HttpAdapter.HeadResponse h = new HttpAdapter.HeadResponse(
                200, 1024L, true, null, lm);
        Manifest m = Manifest.forNewDownload(url, h, 256L);

        assertThat(m.matchesHead(h, 256L)).isTrue();

        HttpAdapter.HeadResponse h2 = new HttpAdapter.HeadResponse(
                200, 1024L, true, null, "Thu, 22 Oct 2015 07:28:00 GMT");
        assertThat(m.matchesHead(h2, 256L)).isFalse();
    }

    @Test
    void unsupportedVersion_rejectsRead() throws Exception {
        Path p = tmp.resolve("future.part.meta");
        Files.writeString(p, """
                version=99
                url=https%3A%2F%2Fexample.com%2Fx
                etag=
                lastModified=
                contentLength=1024
                chunkSize=256
                completed=
                """);

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version 99");
    }

    @Test
    void unknownKey_rejectsRead() throws Exception {
        Path p = tmp.resolve("unknown.part.meta");
        Files.writeString(p, """
                version=2
                url=https%3A%2F%2Fexample.com%2Fx
                etag=
                lastModified=
                contentLength=1024
                chunkSize=256
                completed=
                bonusKey=anything
                """);

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bonusKey");
    }

    @Test
    void missingRequiredKey_rejectsRead() throws Exception {
        Path p = tmp.resolve("missing.part.meta");
        // missing chunkSize
        Files.writeString(p, """
                version=2
                url=https%3A%2F%2Fexample.com%2Fx
                etag=
                lastModified=
                contentLength=1024
                completed=
                """);

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("chunkSize");
    }

    @Test
    void malformedHexInCompleted_rejectsRead() throws Exception {
        Path p = tmp.resolve("badhex.part.meta");
        Files.writeString(p, """
                version=2
                url=https%3A%2F%2Fexample.com%2Fx
                etag=
                lastModified=
                contentLength=1024
                chunkSize=256
                completed=zz
                """);

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("completed");
    }

    @Test
    void blankLineMidFile_rejectsRead() throws Exception {
        Path p = tmp.resolve("blank.part.meta");
        Files.writeString(p, """
                version=2
                url=https%3A%2F%2Fexample.com%2Fx

                etag=
                lastModified=
                contentLength=1024
                chunkSize=256
                completed=
                """);

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("blank line");
    }

    @Test
    void duplicateKey_rejectsRead() throws Exception {
        Path p = tmp.resolve("dupe.part.meta");
        Files.writeString(p, """
                version=2
                url=https%3A%2F%2Fexample.com%2Fx
                etag=
                etag=
                lastModified=
                contentLength=1024
                chunkSize=256
                completed=
                """);

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate key");
    }

    @Test
    void totalChunks_computedFromContentLengthAndChunkSize() {
        URI url = URI.create("https://example.com/x");
        HttpAdapter.HeadResponse h = new HttpAdapter.HeadResponse(
                200, 1500L, true, "\"e\"", null);
        Manifest m = Manifest.forNewDownload(url, h, 1024L);
        assertThat(m.totalChunks()).isEqualTo(2);

        Manifest m2 = Manifest.forNewDownload(url,
                new HttpAdapter.HeadResponse(200, 2048L, true, "\"e\"", null), 1024L);
        assertThat(m2.totalChunks()).isEqualTo(2);
    }
}
