package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class FileAssemblerTest {

    @TempDir Path tmp;

    @Test
    void commitWritesDataAndMovesToDestination() throws Exception {
        byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        Path dest = tmp.resolve("output.bin");

        try (FileAssembler asm = new FileAssembler(dest, false)) {
            ChunkSink sink = asm.sinkAt(0);
            sink.write(ByteBuffer.wrap(data));
            asm.commit();
        }

        assertThat(dest).exists();
        assertThat(Files.readAllBytes(dest)).isEqualTo(data);
    }

    @Test
    void tempFileDeletedAfterCommit() throws Exception {
        Path dest = tmp.resolve("out.bin");
        Path[] temp = new Path[1];

        try (FileAssembler asm = new FileAssembler(dest, false)) {
            temp[0] = asm.tempFile();
            asm.sinkAt(0).write(ByteBuffer.wrap(new byte[]{42}));
            asm.commit();
        }

        assertThat(temp[0]).doesNotExist();
        assertThat(dest).exists();
    }

    @Test
    void abortDeletesTempAndLeavesDestUntouched() throws Exception {
        Path dest = tmp.resolve("out.bin");
        Path[] temp = new Path[1];

        try (FileAssembler asm = new FileAssembler(dest, false)) {
            temp[0] = asm.tempFile();
            asm.sinkAt(0).write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            asm.abort();
        }

        assertThat(temp[0]).doesNotExist();
        assertThat(dest).doesNotExist();
    }

    @Test
    void closeWithoutCommitActsAsAbort() throws Exception {
        Path dest = tmp.resolve("out.bin");
        Path[] temp = new Path[1];

        try (FileAssembler asm = new FileAssembler(dest, false)) {
            temp[0] = asm.tempFile();
            // intentionally NOT committing
        }

        assertThat(temp[0]).doesNotExist();
        assertThat(dest).doesNotExist();
    }

    @Test
    void chunksWrittenAtCorrectOffsets() throws Exception {
        byte[] a = "HELLO".getBytes();
        byte[] b = "WORLD".getBytes();
        Path dest = tmp.resolve("out.bin");

        try (FileAssembler asm = new FileAssembler(dest, false)) {
            // write chunks out of order to prove positional writes work
            asm.sinkAt(a.length).write(ByteBuffer.wrap(b));
            asm.sinkAt(0).write(ByteBuffer.wrap(a));
            asm.commit();
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo("HELLOWORLD".getBytes());
    }

    @Test
    void concurrentChunkWritesProduceCorrectFile() throws Exception {
        int chunks = 8;
        int chunkSize = 1024;
        byte[] full = new byte[chunks * chunkSize];
        for (int i = 0; i < full.length; i++) full[i] = (byte) (i & 0xFF);

        Path dest = tmp.resolve("parallel.bin");
        try (FileAssembler asm = new FileAssembler(dest, false)) {
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < chunks; i++) {
                    final int idx = i;
                    ex.submit(() -> {
                        byte[] chunk = Arrays.copyOfRange(full, idx * chunkSize, (idx + 1) * chunkSize);
                        try {
                            asm.sinkAt((long) idx * chunkSize).write(ByteBuffer.wrap(chunk));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    });
                }
            }
            asm.commit();
        }

        assertThat(Files.readAllBytes(dest)).isEqualTo(full);
    }

    @Test
    void chunkSinkAcceptThrowsUncheckedOnIo() throws Exception {
        Path dest = tmp.resolve("out.bin");
        try (FileAssembler asm = new FileAssembler(dest, false)) {
            ChunkSink sink = asm.sinkAt(0);
            // close the channel underneath to provoke an IOException
            asm.abort(); // closes and deletes
            // sink.accept on a closed channel should throw UncheckedIOException
            assertThatExceptionOfType(java.io.UncheckedIOException.class)
                    .isThrownBy(() -> sink.accept(ByteBuffer.wrap(new byte[]{1})));
        }
    }

    @Test
    void commitAfterAbortThrows() throws Exception {
        Path dest = tmp.resolve("out.bin");
        FileAssembler asm = new FileAssembler(dest, false);
        asm.abort();
        assertThatIllegalStateException().isThrownBy(asm::commit);
    }

    @Test
    void sha256MatchesAfterParallelWrite() throws Exception {
        byte[] data = new byte[4 * 1024 * 1024]; // 4 MiB
        new java.util.Random(99).nextBytes(data);
        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(data);

        int chunkSize = 512 * 1024; // 512 KiB
        Path dest = tmp.resolve("big.bin");
        try (FileAssembler asm = new FileAssembler(dest, false)) {
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (long offset = 0; offset < data.length; offset += chunkSize) {
                    final long off = offset;
                    final int len = (int) Math.min(chunkSize, data.length - off);
                    ex.submit(() -> {
                        try {
                            asm.sinkAt(off).write(ByteBuffer.wrap(data, (int) off, len));
                        } catch (IOException e) { throw new RuntimeException(e); }
                        return null;
                    });
                }
            }
            asm.commit();
        }

        byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dest));
        assertThat(actualHash).isEqualTo(expectedHash);
    }
}
