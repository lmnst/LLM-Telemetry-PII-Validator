package io.github.lmnst.downloader;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sidecar metadata for a partial download. Lives at {@code <dest>.part.meta}
 * next to the {@code <dest>.part} data file. Updated atomically (write tmp,
 * fsync, rename) after each chunk completes its write and integrity check.
 *
 * <p>Read by {@link ResumeStrategy#RESUME_IF_VALID} at the start of a download
 * to decide whether to resume; deleted on successful commit; preserved on
 * failure so the user can retry with {@code --resume}.
 *
 * <p>The on-disk format is a line-oriented {@code key=value} stream. Keys
 * appear in a fixed order for readability under {@code cat}; values are
 * URL-encoded so a literal newline, equals sign, or non-ASCII character in
 * the URL or validators cannot break the parser. The completed bitmap is
 * hex because it is already a byte stream and round-trips through {@link
 * HexFormat} faster than through a percent-encoder.
 */
final class Manifest {

    static final int CURRENT_VERSION = 2;
    private static final String SUFFIX = ".part.meta";

    private static final String K_VERSION         = "version";
    private static final String K_URL             = "url";
    private static final String K_ETAG            = "etag";
    private static final String K_LAST_MODIFIED   = "lastModified";
    private static final String K_CONTENT_LENGTH  = "contentLength";
    private static final String K_CHUNK_SIZE      = "chunkSize";
    private static final String K_COMPLETED       = "completed";

    private static final Set<String> REQUIRED_KEYS = Set.of(
            K_VERSION, K_URL, K_CONTENT_LENGTH, K_CHUNK_SIZE, K_COMPLETED);
    private static final Set<String> RECOGNIZED_KEYS = Set.of(
            K_VERSION, K_URL, K_ETAG, K_LAST_MODIFIED,
            K_CONTENT_LENGTH, K_CHUNK_SIZE, K_COMPLETED);

    final int version;
    final URI url;
    final String etag;          // nullable
    final String lastModified;  // nullable
    final long contentLength;
    final long chunkSize;
    final BitSet completed;     // bit i = chunk i complete

    private Manifest(URI url, String etag, String lastModified,
                     long contentLength, long chunkSize, BitSet completed) {
        this.version = CURRENT_VERSION;
        this.url = Objects.requireNonNull(url, "url");
        this.etag = etag;
        this.lastModified = lastModified;
        if (contentLength < 0) throw new IllegalArgumentException("contentLength < 0");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize <= 0");
        this.contentLength = contentLength;
        this.chunkSize = chunkSize;
        this.completed = Objects.requireNonNull(completed, "completed");
    }

    static Manifest forNewDownload(URI url, HttpAdapter.HeadResponse head, long chunkSize) {
        return new Manifest(url, head.etag(), head.lastModified(),
                head.contentLength(), chunkSize, new BitSet());
    }

    static Path pathFor(Path destination) {
        Path parent = destination.getParent();
        String name = destination.getFileName().toString() + SUFFIX;
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    int totalChunks() {
        if (chunkSize == 0) return 0;
        return (int) ((contentLength + chunkSize - 1) / chunkSize);
    }

    boolean isCompleted(int chunkIndex) {
        return completed.get(chunkIndex);
    }

    void markComplete(int chunkIndex) {
        completed.set(chunkIndex);
    }

    boolean isFullyCompleted() {
        return completed.cardinality() == totalChunks();
    }

    /**
     * Returns true iff this manifest's recorded validators match the current
     * HEAD response and the requested chunkSize. Any mismatch invalidates the
     * partial download, the caller surfaces RESOURCE_CHANGED and (typically)
     * deletes the sidecar so a fresh download can proceed.
     */
    boolean matchesHead(HttpAdapter.HeadResponse head, long currentChunkSize) {
        if (this.contentLength != head.contentLength()) return false;
        if (this.chunkSize != currentChunkSize) return false;
        if (!Objects.equals(this.etag, head.etag())) return false;
        // lastModified is informational only when an ETag is present; we
        // intentionally do not require it to match if the ETag does.
        if (this.etag == null && !Objects.equals(this.lastModified, head.lastModified())) {
            return false;
        }
        return true;
    }

    /**
     * Atomically writes the manifest: write tmp, fsync, rename onto target.
     * After this returns the new content is durable on disk.
     */
    void writeAtomically(Path manifestPath) throws IOException {
        Path tmp = manifestPath.resolveSibling(manifestPath.getFileName() + ".tmp");
        byte[] bytes = serialize().getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        Files.move(tmp, manifestPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    static Manifest read(Path manifestPath) throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        Map<String, String> tokens = parseLines(content);

        int version = parseIntField(tokens, K_VERSION);
        if (version != CURRENT_VERSION) {
            throw new IOException("unsupported manifest version " + version
                    + " (expected " + CURRENT_VERSION + ")");
        }

        URI url;
        try {
            url = URI.create(decode(tokens.get(K_URL)));
        } catch (IllegalArgumentException e) {
            throw new IOException("manifest field '" + K_URL + "' is not a valid URI: "
                    + e.getMessage());
        }
        String etag         = decodeOrNull(tokens.get(K_ETAG));
        String lastModified = decodeOrNull(tokens.get(K_LAST_MODIFIED));
        long contentLength  = parseLongField(tokens, K_CONTENT_LENGTH);
        long chunkSize      = parseLongField(tokens, K_CHUNK_SIZE);
        BitSet completed    = parseHexBitSet(tokens.get(K_COMPLETED));

        return new Manifest(url, etag, lastModified, contentLength, chunkSize, completed);
    }

    // ── serialization helpers ────────────────────────────────────────────────

    private String serialize() {
        StringBuilder sb = new StringBuilder(160);
        sb.append(K_VERSION).append('=').append(version).append('\n');
        sb.append(K_URL).append('=').append(encode(url.toString())).append('\n');
        sb.append(K_ETAG).append('=').append(encodeOrEmpty(etag)).append('\n');
        sb.append(K_LAST_MODIFIED).append('=').append(encodeOrEmpty(lastModified)).append('\n');
        sb.append(K_CONTENT_LENGTH).append('=').append(contentLength).append('\n');
        sb.append(K_CHUNK_SIZE).append('=').append(chunkSize).append('\n');
        sb.append(K_COMPLETED).append('=')
                .append(HexFormat.of().formatHex(completed.toByteArray())).append('\n');
        return sb.toString();
    }

    private static Map<String, String> parseLines(String content) throws IOException {
        Map<String, String> tokens = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        // split with -1 keeps a trailing empty after a final '\n'; legitimate
        // and accepted. Any blank line in the middle is rejected as garbage.
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) {
                if (i == lines.length - 1) continue; // trailing newline
                throw new IOException("manifest contains a blank line at line " + (i + 1));
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IOException("manifest line " + (i + 1)
                        + " is missing '=': " + truncated(line));
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (!RECOGNIZED_KEYS.contains(key)) {
                throw new IOException("manifest contains unknown key: " + key);
            }
            if (tokens.put(key, value) != null) {
                throw new IOException("manifest contains duplicate key: " + key);
            }
        }
        for (String required : REQUIRED_KEYS) {
            if (!tokens.containsKey(required)) {
                throw new IOException("manifest is missing required key: " + required);
            }
        }
        return tokens;
    }

    private static int parseIntField(Map<String, String> tokens, String key) throws IOException {
        try {
            return Integer.parseInt(tokens.get(key));
        } catch (NumberFormatException e) {
            throw new IOException("manifest field '" + key + "' is not an integer: "
                    + tokens.get(key));
        }
    }

    private static long parseLongField(Map<String, String> tokens, String key) throws IOException {
        try {
            return Long.parseLong(tokens.get(key));
        } catch (NumberFormatException e) {
            throw new IOException("manifest field '" + key + "' is not a long: "
                    + tokens.get(key));
        }
    }

    private static BitSet parseHexBitSet(String hex) throws IOException {
        try {
            byte[] bytes = HexFormat.of().parseHex(hex);
            return BitSet.valueOf(bytes);
        } catch (IllegalArgumentException e) {
            throw new IOException("manifest field '" + K_COMPLETED
                    + "' is not valid hex: " + e.getMessage());
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encodeOrEmpty(String s) {
        return s == null ? "" : encode(s);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String decodeOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return decode(s);
    }

    private static String truncated(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
