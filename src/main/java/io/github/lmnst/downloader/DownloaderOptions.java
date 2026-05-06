package io.github.lmnst.downloader;

import java.time.Duration;

/**
 * Immutable configuration for a {@link Downloader}. Construct via
 * {@link #defaults()} or {@link #builder()}.
 *
 * @param chunkSize           bytes per chunk (default 8 MiB)
 * @param parallelism         maximum concurrent ranged GETs (default 8)
 * @param connectTimeout      socket connect timeout
 * @param requestTimeout      per-request timeout, including body transfer
 * @param maxRetriesPerChunk  retries beyond the first attempt for a single chunk
 * @param retryBaseDelay      base delay for exponential backoff with full jitter
 * @param userAgent           value of the {@code User-Agent} header
 * @param expectedDigest      optional digest to verify against, or null
 * @param resumeStrategy      whether to reuse {@code .part}/{@code .part.meta}
 * @param progressListener    receives {@link ProgressEvent}s; defaults to no-op
 */
public record DownloaderOptions(
        long chunkSize,
        int parallelism,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRetriesPerChunk,
        Duration retryBaseDelay,
        String userAgent,
        ExpectedDigest expectedDigest,
        ResumeStrategy resumeStrategy,
        ProgressListener progressListener
) {
    /**
     * Validates components.
     *
     * @throws IllegalArgumentException if any required component is invalid
     */
    public DownloaderOptions {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0, got: " + chunkSize);
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism must be > 0, got: " + parallelism);
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero())
            throw new IllegalArgumentException("connectTimeout must be positive");
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalArgumentException("requestTimeout must be positive");
        if (maxRetriesPerChunk < 0)
            throw new IllegalArgumentException("maxRetriesPerChunk must be >= 0, got: " + maxRetriesPerChunk);
        if (retryBaseDelay == null || retryBaseDelay.isNegative())
            throw new IllegalArgumentException("retryBaseDelay must be non-negative");
        if (userAgent == null || userAgent.isBlank())
            throw new IllegalArgumentException("userAgent must not be blank");
        if (resumeStrategy == null) throw new IllegalArgumentException("resumeStrategy must not be null");
        if (progressListener == null) throw new IllegalArgumentException("progressListener must not be null");
        // expectedDigest may be null (no integrity check requested)
    }

    /** {@return options with all defaults} */
    public static DownloaderOptions defaults() {
        return builder().build();
    }

    /** {@return a fresh {@link Builder}} */
    public static Builder builder() { return new Builder(); }

    /** Mutable builder for {@link DownloaderOptions}; obtain via {@link #builder()}. */
    public static final class Builder {
        private long chunkSize = 8L * 1024 * 1024;
        private int parallelism = 8;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private int maxRetriesPerChunk = 3;
        private Duration retryBaseDelay = Duration.ofMillis(200);
        private String userAgent = "parallel-downloader/1.0 (+java.net.http)";
        private ExpectedDigest expectedDigest = null;
        private ResumeStrategy resumeStrategy = ResumeStrategy.FRESH;
        private ProgressListener progressListener = ProgressListener.NO_OP;

        Builder() {}

        /**
         * Sets the chunk size in bytes (must be &gt; 0).
         * @param v bytes per chunk
         * @return this builder
         */
        public Builder chunkSize(long v)              { this.chunkSize = v; return this; }

        /**
         * Sets the maximum concurrent ranged GETs (must be &gt; 0).
         * @param v parallelism cap
         * @return this builder
         */
        public Builder parallelism(int v)             { this.parallelism = v; return this; }

        /**
         * Sets the socket connect timeout.
         * @param v duration
         * @return this builder
         */
        public Builder connectTimeout(Duration v)     { this.connectTimeout = v; return this; }

        /**
         * Sets the per-request timeout.
         * @param v duration
         * @return this builder
         */
        public Builder requestTimeout(Duration v)     { this.requestTimeout = v; return this; }

        /**
         * Sets the per-chunk retry budget (beyond the first attempt).
         * @param v retry count
         * @return this builder
         */
        public Builder maxRetriesPerChunk(int v)      { this.maxRetriesPerChunk = v; return this; }

        /**
         * Sets the base delay for exponential backoff with full jitter.
         * @param v base delay
         * @return this builder
         */
        public Builder retryBaseDelay(Duration v)     { this.retryBaseDelay = v; return this; }

        /**
         * Sets the {@code User-Agent} header value.
         * @param v user agent string
         * @return this builder
         */
        public Builder userAgent(String v)            { this.userAgent = v; return this; }

        /**
         * Sets the resume policy.
         * @param v resume strategy
         * @return this builder
         */
        public Builder resumeStrategy(ResumeStrategy v) { this.resumeStrategy = v; return this; }

        /**
         * Sets the progress listener; pass {@link ProgressListener#NO_OP} to disable.
         * @param v listener
         * @return this builder
         */
        public Builder progressListener(ProgressListener v) { this.progressListener = v; return this; }

        /**
         * Configures an expected digest. The download fails with
         * {@link DownloadError#INTEGRITY_FAILURE} if the temp file's digest
         * does not match before commit.
         *
         * @param algorithm digest algorithm
         * @param bytes     expected bytes (length must match algorithm)
         * @return this builder
         */
        public Builder expectedDigest(Algorithm algorithm, byte[] bytes) {
            this.expectedDigest = new ExpectedDigest(algorithm, bytes);
            return this;
        }

        /** {@return a new {@link DownloaderOptions} with the configured values} */
        public DownloaderOptions build() {
            return new DownloaderOptions(chunkSize, parallelism, connectTimeout,
                    requestTimeout, maxRetriesPerChunk, retryBaseDelay, userAgent,
                    expectedDigest, resumeStrategy, progressListener);
        }
    }
}
