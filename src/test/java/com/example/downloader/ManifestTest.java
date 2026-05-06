package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
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

        Path p = tmp.resolve("dest.bin.part.json");
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

        Path p = tmp.resolve("x.part.json");
        m.writeAtomically(p);
        Manifest reread = Manifest.read(p);

        assertThat(reread.etag).isNull();
        assertThat(reread.lastModified).isNull();
    }

    @Test
    void atomicWrite_doesNotLeaveTmpFile() throws Exception {
        URI url = URI.create("https://example.com/x");
        HttpAdapter.HeadResponse head = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"e\"", null);
        Manifest m = Manifest.forNewDownload(url, head, 256L);

        Path p = tmp.resolve("x.part.json");
        m.writeAtomically(p);

        try (var stream = Files.list(tmp)) {
            assertThat(stream.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")))
                    .as("no .tmp file should remain after atomic write")
                    .isTrue();
        }
    }

    @Test
    void matchesHead_etagAndContentLengthAndChunkSize() throws Exception {
        URI url = URI.create("https://example.com/x");
        HttpAdapter.HeadResponse h1 = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"v1\"", null);
        Manifest m = Manifest.forNewDownload(url, h1, 256L);

        // Same head, same chunk size → match
        assertThat(m.matchesHead(h1, 256L)).isTrue();

        // Different ETag → no match
        HttpAdapter.HeadResponse h2 = new HttpAdapter.HeadResponse(
                200, 1024L, true, "\"v2\"", null);
        assertThat(m.matchesHead(h2, 256L)).isFalse();

        // Different contentLength → no match
        HttpAdapter.HeadResponse h3 = new HttpAdapter.HeadResponse(
                200, 2048L, true, "\"v1\"", null);
        assertThat(m.matchesHead(h3, 256L)).isFalse();

        // Different chunkSize → no match
        assertThat(m.matchesHead(h1, 512L)).isFalse();
    }

    @Test
    void matchesHead_fallsBackToLastModifiedWhenEtagMissing() throws Exception {
        URI url = URI.create("https://example.com/x");
        String lm = "Wed, 21 Oct 2015 07:28:00 GMT";
        HttpAdapter.HeadResponse h = new HttpAdapter.HeadResponse(
                200, 1024L, true, null, lm);
        Manifest m = Manifest.forNewDownload(url, h, 256L);

        // Same Last-Modified → match
        assertThat(m.matchesHead(h, 256L)).isTrue();

        // Different Last-Modified → no match
        HttpAdapter.HeadResponse h2 = new HttpAdapter.HeadResponse(
                200, 1024L, true, null, "Thu, 22 Oct 2015 07:28:00 GMT");
        assertThat(m.matchesHead(h2, 256L)).isFalse();
    }

    @Test
    void unsupportedVersion_rejectsRead() throws Exception {
        Path p = tmp.resolve("future.part.json");
        Files.writeString(p, "{\"version\": 99, \"url\": \"https://x\"}");

        assertThatThrownBy(() -> Manifest.read(p))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version 99");
    }

    @Test
    void totalChunks_computedFromContentLengthAndChunkSize() throws Exception {
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
