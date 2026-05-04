package com.example.downloader;

import java.time.Duration;

public record DownloaderOptions(
        long chunkSize,
        int parallelism,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRetriesPerChunk,
        Duration retryBaseDelay,
        String userAgent
) {
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
    }

    public static DownloaderOptions defaults() {
        return new DownloaderOptions(
                8L * 1024 * 1024,       // 8 MiB: amortizes TCP slow-start; 8 chunks of 64 MiB = meaningful parallelism
                8,                       // typical sweet spot for a single host on a residential/office link
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                3,
                Duration.ofMillis(200),
                "parallel-downloader/1.0 (+java.net.http)"
        );
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long chunkSize = 8L * 1024 * 1024;
        private int parallelism = 8;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private int maxRetriesPerChunk = 3;
        private Duration retryBaseDelay = Duration.ofMillis(200);
        private String userAgent = "parallel-downloader/1.0 (+java.net.http)";

        public Builder chunkSize(long v)            { this.chunkSize = v; return this; }
        public Builder parallelism(int v)           { this.parallelism = v; return this; }
        public Builder connectTimeout(Duration v)   { this.connectTimeout = v; return this; }
        public Builder requestTimeout(Duration v)   { this.requestTimeout = v; return this; }
        public Builder maxRetriesPerChunk(int v)    { this.maxRetriesPerChunk = v; return this; }
        public Builder retryBaseDelay(Duration v)   { this.retryBaseDelay = v; return this; }
        public Builder userAgent(String v)          { this.userAgent = v; return this; }

        public DownloaderOptions build() {
            return new DownloaderOptions(chunkSize, parallelism, connectTimeout,
                    requestTimeout, maxRetriesPerChunk, retryBaseDelay, userAgent);
        }
    }
}
