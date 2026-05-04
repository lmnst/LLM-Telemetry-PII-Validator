package com.example.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory HttpAdapter for unit tests. Serves a fixed byte array body.
 */
public final class FakeHttpAdapter implements HttpAdapter {

    private final byte[] body;
    private final HeadResponse headResponse;
    // 0-based GET call index → IOException to throw
    private final Map<Integer, IOException> getFailures;
    // when true, every GET returns 200 + full body (no range support mid-flight)
    private final boolean forceFullGet;

    private final AtomicInteger headCallCount = new AtomicInteger();
    private final AtomicInteger getCallCount  = new AtomicInteger();

    private FakeHttpAdapter(byte[] body, HeadResponse headResponse,
                            Map<Integer, IOException> getFailures, boolean forceFullGet) {
        this.body          = Arrays.copyOf(body, body.length);
        this.headResponse  = headResponse;
        this.getFailures   = Map.copyOf(getFailures);
        this.forceFullGet  = forceFullGet;
    }

    @Override
    public HeadResponse head(URI uri) {
        headCallCount.incrementAndGet();
        return headResponse;
    }

    @Override
    public GetResponse get(URI uri, ByteRange range, Consumer<ByteBuffer> sink, CancelToken cancel)
            throws IOException, InterruptedException {

        int attempt = getCallCount.getAndIncrement();
        if (cancel.isCancelled()) throw new InterruptedException("cancelled before GET");

        IOException failure = getFailures.get(attempt);
        if (failure != null) throw failure;

        if (forceFullGet || range == null) {
            sink.accept(ByteBuffer.wrap(body).asReadOnlyBuffer());
            return new GetResponse(200, body.length, null);
        }

        int from = (int) range.offset();
        int len  = (int) range.length();
        sink.accept(ByteBuffer.wrap(body, from, len).asReadOnlyBuffer());
        String contentRange = "bytes " + range.offset() + "-" + range.lastByte() + "/" + body.length;
        return new GetResponse(206, len, contentRange);
    }

    public int headCallCount() { return headCallCount.get(); }
    public int getCallCount()  { return getCallCount.get(); }

    // ── factory helpers ──────────────────────────────────────────────────────

    public static FakeHttpAdapter parallelCapable(byte[] body) {
        return builder(body).build();
    }

    public static FakeHttpAdapter noRangeSupport(byte[] body) {
        return builder(body).acceptRanges(false).build();
    }

    public static Builder builder(byte[] body) { return new Builder(body); }

    // ── builder ──────────────────────────────────────────────────────────────

    public static final class Builder {
        private final byte[] body;
        private boolean acceptRanges = true;
        private String etag = "\"fake-etag\"";
        private final Map<Integer, IOException> getFailures = new HashMap<>();
        private boolean forceFullGet = false;

        private Builder(byte[] body) { this.body = body; }

        public Builder acceptRanges(boolean v)  { this.acceptRanges = v; return this; }
        public Builder etag(String v)           { this.etag = v; return this; }
        public Builder forceFullGet(boolean v)  { this.forceFullGet = v; return this; }

        /** Throw e on the Nth GET call (0-based). */
        public Builder failGetOnAttempt(int n, IOException e) {
            getFailures.put(n, e);
            return this;
        }

        public FakeHttpAdapter build() {
            HeadResponse head = new HeadResponse(200, body.length, acceptRanges, etag);
            return new FakeHttpAdapter(body, head, getFailures, forceFullGet);
        }
    }
}
