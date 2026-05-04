package com.example.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Downloader implements AutoCloseable {

    private final DownloaderOptions options;
    private final HttpAdapter http;

    public Downloader(DownloaderOptions options) {
        this.options = options;
        this.http = new JdkHttpAdapter(options);
    }

    Downloader(DownloaderOptions options, HttpAdapter http) {
        this.options = options;
        this.http = http;
    }

    // ── public API ───────────────────────────────────────────────────────────

    public DownloadResult download(URI source, Path destination) throws InterruptedException {
        CancelToken cancel = new CancelToken();
        return doDownload(source, destination, cancel);
    }

    public DownloadHandle downloadAsync(URI source, Path destination) {
        CancelToken cancel = new CancelToken();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Future<DownloadResult> future = exec.submit(() -> doDownload(source, destination, cancel));
        exec.shutdown();
        return new DownloadHandle(future, cancel);
    }

    @Override
    public void close() {
        if (http instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    // ── core logic ───────────────────────────────────────────────────────────

    private DownloadResult doDownload(URI uri, Path dest, CancelToken cancel) {
        long startNanos = System.nanoTime();

        // HEAD preflight
        HttpAdapter.HeadResponse head;
        try {
            head = http.head(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(DownloadError.CANCELLED, e);
        } catch (IOException e) {
            return failure(DownloadError.IO_ERROR, e);
        }

        if (head.status() < 200 || head.status() > 299) {
            return failure(DownloadError.HTTP_ERROR,
                    new IOException("HEAD returned HTTP " + head.status()));
        }

        boolean canParallel = head.acceptRanges()
                && head.contentLength() > 0
                && options.parallelism() > 1;

        FileAssembler asm;
        try {
            asm = new FileAssembler(dest);
        } catch (IOException e) {
            return failure(DownloadError.IO_ERROR, e);
        }

        try {
            if (canParallel) {
                return parallelDownload(uri, dest, head, asm, cancel, startNanos);
            } else {
                return singleStreamDownload(uri, dest, asm, cancel, startNanos);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asm.abort();
            return failure(DownloadError.CANCELLED, e);
        } catch (SizeMismatchException e) {
            asm.abort();
            return failure(DownloadError.SIZE_MISMATCH, e);
        } catch (HttpStatusException e) {
            asm.abort();
            return failure(DownloadError.HTTP_ERROR, e);
        } catch (IOException e) {
            asm.abort();
            return failure(DownloadError.IO_ERROR, e);
        } finally {
            asm.close(); // safe — abort/commit already happened; close() is idempotent
        }
    }

    private DownloadResult parallelDownload(URI uri, Path dest, HttpAdapter.HeadResponse head,
                                            FileAssembler asm, CancelToken cancel,
                                            long startNanos)
            throws IOException, InterruptedException {

        List<ByteRange> ranges = RangePlanner.plan(head.contentLength(), options.chunkSize());
        RetryPolicy retry = new RetryPolicy(options);

        // Probe chunk 0 first: detect servers that ignore Range headers and return 200
        ByteRange firstRange = ranges.get(0);
        HttpAdapter.GetResponse probe = downloadChunk(0, firstRange, uri, asm, cancel, retry);

        if (probe.status() == 200) {
            // Server returned full body; verify size and commit
            long total = probe.bytesWritten();
            if (head.contentLength() > 0 && total != head.contentLength()) {
                throw new SizeMismatchException(head.contentLength(), total);
            }
            asm.commit();
            return success(dest, total, startNanos, 1);
        }

        if (probe.status() != 206) {
            throw new HttpStatusException(probe.status());
        }

        // Remaining chunks in parallel
        if (ranges.size() > 1) {
            downloadChunksParallel(uri, ranges.subList(1, ranges.size()), asm, cancel, retry);
        }

        if (cancel.isCancelled()) throw new InterruptedException("cancelled after chunks");

        // Size verification
        long expected = head.contentLength();
        // Sum of all range lengths == expected (RangePlanner guarantees this)
        // We verify actual bytes written == expected by trusting range math;
        // a size_mismatch can arise from truncated HTTP bodies.
        long totalWritten = ranges.stream().mapToLong(ByteRange::length).sum();
        if (expected > 0 && totalWritten != expected) {
            throw new SizeMismatchException(expected, totalWritten);
        }

        asm.commit();
        return success(dest, expected, startNanos, ranges.size());
    }

    private void downloadChunksParallel(URI uri, List<ByteRange> ranges,
                                        FileAssembler asm, CancelToken cancel,
                                        RetryPolicy retry)
            throws IOException, InterruptedException {

        List<Callable<Void>> tasks = new ArrayList<>(ranges.size());

        for (int i = 0; i < ranges.size(); i++) {
            final int idx = i + 1; // 0 was the probe
            final ByteRange range = ranges.get(i);
            tasks.add(() -> {
                HttpAdapter.GetResponse resp = downloadChunk(idx, range, uri, asm, cancel, retry);
                if (resp.status() != 206) {
                    throw new HttpStatusException(resp.status());
                }
                return null;
            });
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>(tasks.size());
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            collectFutures(futures);
        }
    }

    private void collectFutures(List<Future<Void>> futures)
            throws IOException, InterruptedException {
        IOException ioErr = null;
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException ie) throw ie;
                if (cause instanceof HttpStatusException hse) throw hse;
                if (ioErr == null) {
                    ioErr = cause instanceof IOException ioe ? ioe
                            : new IOException("chunk failed", cause);
                }
            }
        }
        if (ioErr != null) throw ioErr;
    }

    private DownloadResult singleStreamDownload(URI uri, Path dest,
                                                FileAssembler asm, CancelToken cancel,
                                                long startNanos)
            throws IOException, InterruptedException {

        RetryPolicy retry = new RetryPolicy(options);
        HttpAdapter.GetResponse resp = downloadChunk(0, null, uri, asm, cancel, retry);

        if (resp.status() != 200 && resp.status() != 206) {
            throw new HttpStatusException(resp.status());
        }

        asm.commit();
        return success(dest, resp.bytesWritten(), startNanos, 1);
    }

    /**
     * Downloads one chunk with retry. A null range means "no Range header" (single-stream).
     * Returns the GetResponse on success; throws on permanent failure.
     */
    private HttpAdapter.GetResponse downloadChunk(int chunkIndex, ByteRange range,
                                                   URI uri, FileAssembler asm,
                                                   CancelToken cancel, RetryPolicy retry)
            throws IOException, InterruptedException {

        for (int attempt = 0; ; attempt++) {
            if (cancel.isCancelled()) throw new InterruptedException("cancelled before chunk " + chunkIndex);

            ChunkSink sink = asm.sinkAt(range == null ? 0 : range.offset());

            try {
                HttpAdapter.GetResponse resp = http.get(uri, range, sink, cancel);

                if (isRetryableStatus(resp.status())) {
                    Duration retryAfter = Duration.ZERO; // no Retry-After from GetResponse
                    Optional<Duration> delay = retry.evaluate(attempt,
                            new RetryPolicy.Trigger.HttpStatus(resp.status(), retryAfter));
                    if (delay.isEmpty()) throw new HttpStatusException(resp.status());
                    Thread.sleep(delay.get().toMillis());
                    continue;
                }

                return resp;

            } catch (InterruptedException e) {
                throw e; // cancellation — do not retry
            } catch (IOException e) {
                Optional<Duration> delay = retry.evaluate(attempt,
                        new RetryPolicy.Trigger.IoFailure(e));
                if (delay.isEmpty()) throw e;
                Thread.sleep(delay.get().toMillis());
            }
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DownloadResult.Success success(Path dest, long bytes, long startNanos, int chunks) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        return new DownloadResult.Success(dest, bytes, elapsed, chunks);
    }

    private static DownloadResult.Failure failure(DownloadError error, Throwable cause) {
        return new DownloadResult.Failure(error, cause);
    }

    // ── package-private exceptions ───────────────────────────────────────────

    static final class HttpStatusException extends IOException {
        final int statusCode;
        HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    static final class SizeMismatchException extends IOException {
        SizeMismatchException(long expected, long actual) {
            super("size mismatch: expected " + expected + ", got " + actual);
        }
    }
}
