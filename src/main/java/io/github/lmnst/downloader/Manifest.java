package io.github.lmnst.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sidecar metadata for a partial download. Lives at <dest>.part.json next to
 * the <dest>.part data file. Updated atomically (write tmp + fsync + rename)
 * after each chunk completes its write and integrity check.
 *
 * Read by RESUME_IF_VALID at the start of a download to decide whether to
 * resume; deleted on successful commit; preserved on failure so the user
 * can retry with --resume.
 */
final class Manifest {

    static final int CURRENT_VERSION = 1;
    private static final String SUFFIX = ".part.json";

    // Matches `"key": <value>` where value is a JSON string, integer, or null.
    private static final Pattern FIELD = Pattern.compile(
            "\"(\\w+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null|-?\\d+)");

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
        Map<String, String> tokens = new HashMap<>();
        Matcher m = FIELD.matcher(content);
        while (m.find()) tokens.put(m.group(1), m.group(2));

        String versionRaw = tokens.get("version");
        if (versionRaw == null) throw new IOException("manifest missing 'version'");
        int version = Integer.parseInt(versionRaw);
        if (version != CURRENT_VERSION) {
            throw new IOException("unsupported manifest version " + version
                    + " (expected " + CURRENT_VERSION + ")");
        }

        URI url = URI.create(unquote(required(tokens, "url")));
        String etag = unquoteOrNull(tokens.get("etag"));
        String lastModified = unquoteOrNull(tokens.get("lastModified"));
        long contentLength = Long.parseLong(required(tokens, "contentLength"));
        long chunkSize = Long.parseLong(required(tokens, "chunkSize"));
        String completedHex = unquote(required(tokens, "completed"));
        BitSet completed = BitSet.valueOf(HexFormat.of().parseHex(completedHex));

        return new Manifest(url, etag, lastModified, contentLength, chunkSize, completed);
    }

    // ── serialization helpers ────────────────────────────────────────────────

    private String serialize() {
        return "{\n"
                + "  \"version\": " + version + ",\n"
                + "  \"url\": " + jsonString(url.toString()) + ",\n"
                + "  \"etag\": " + (etag == null ? "null" : jsonString(etag)) + ",\n"
                + "  \"lastModified\": " + (lastModified == null ? "null" : jsonString(lastModified)) + ",\n"
                + "  \"contentLength\": " + contentLength + ",\n"
                + "  \"chunkSize\": " + chunkSize + ",\n"
                + "  \"completed\": " + jsonString(HexFormat.of().formatHex(completed.toByteArray())) + "\n"
                + "}\n";
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String required(Map<String, String> tokens, String key) throws IOException {
        String v = tokens.get(key);
        if (v == null) throw new IOException("manifest missing '" + key + "'");
        return v;
    }

    private static String unquoteOrNull(String token) {
        if (token == null || token.equals("null")) return null;
        return unquote(token);
    }

    private static String unquote(String token) {
        if (!token.startsWith("\"") || !token.endsWith("\"") || token.length() < 2) {
            throw new IllegalArgumentException("expected JSON string: " + token);
        }
        String inner = token.substring(1, token.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char n = inner.charAt(++i);
                switch (n) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    default   -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
