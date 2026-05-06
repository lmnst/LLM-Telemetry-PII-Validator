package io.github.lmnst.downloader;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Manages the .part data file (and its .part.json manifest sidecar) used during
 * download assembly. The temp path is deterministic, <dest>.part, so a
 * RESUME_IF_VALID restart can re-open the same file and extend it.
 *
 * <ul>
 *   <li>Fresh mode: any existing .part / .part.json files are deleted up front;
 *       abort() also deletes them. Failure leaves no artifacts behind.
 *   <li>Resume mode: existing files are preserved at construction; abort()
 *       leaves both files in place so the caller can retry --resume.
 * </ul>
 *
 * commit() always deletes the manifest after the atomic move; the manifest is
 * only useful while the download is in progress.
 */
final class FileAssembler implements Closeable {

    private static final String PART_SUFFIX = ".part";

    private final Path destination;
    private final Path tempFile;
    private final Path manifestFile;
    private final FileChannel channel;
    private final boolean resumeMode;

    private boolean closed = false;

    FileAssembler(Path destination, boolean resumeMode) throws IOException {
        if (Files.isDirectory(destination)) {
            throw new IOException("destination is a directory: " + destination);
        }
        this.destination = destination;
        this.resumeMode = resumeMode;

        Path dir = destination.getParent();
        if (dir == null) dir = Path.of(".");

        String baseName = destination.getFileName().toString();
        this.tempFile = dir.resolve(baseName + PART_SUFFIX);
        this.manifestFile = Manifest.pathFor(destination);

        if (!resumeMode) {
            // FRESH: any prior partial is wiped, see ResumeStrategy javadoc.
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(manifestFile);
        }

        this.channel = FileChannel.open(tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
                StandardOpenOption.CREATE);
    }

    /** Returns a sink that writes at the given byte offset in the temp file. */
    ChunkSink sinkAt(long offset) {
        return new ChunkSink(channel, offset);
    }

    /**
     * Fsyncs the temp file, atomically moves it to the destination, and deletes
     * the manifest sidecar. After this call the assembler is closed.
     */
    void commit() throws IOException {
        checkOpen();
        channel.force(true);
        channel.close();
        closed = true;
        Files.move(tempFile, destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(manifestFile);
    }

    /**
     * Closes the underlying channel and (in fresh mode) deletes both the .part
     * and .part.json files. In resume mode both files are preserved so the user
     * can retry the download with --resume. Idempotent.
     */
    void abort() {
        if (!closed) {
            closed = true;
            try { channel.close(); } catch (IOException ignored) {}
        }
        if (!resumeMode) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(manifestFile); } catch (IOException ignored) {}
        }
    }

    /** Calls abort() if not already committed; implements try-with-resources. */
    @Override
    public void close() {
        if (!closed) abort();
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("FileAssembler already closed");
    }

    Path tempFile() { return tempFile; }
    Path manifestFile() { return manifestFile; }
}
