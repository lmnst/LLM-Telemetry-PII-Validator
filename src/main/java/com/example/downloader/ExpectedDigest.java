package com.example.downloader;

/**
 * Caller-supplied expected digest for a download. Configured via
 * {@link DownloaderOptions.Builder#expectedDigest(Algorithm, byte[])};
 * the bytes are defensively copied on construction and on access.
 *
 * @param algorithm digest algorithm
 * @param bytes     expected digest bytes; length must match
 *                  {@code algorithm.digestLengthBytes()}
 */
public record ExpectedDigest(Algorithm algorithm, byte[] bytes) {

    /**
     * Validates the components and defensively copies the digest bytes.
     *
     * @throws IllegalArgumentException if {@code algorithm} or {@code bytes}
     *         is null, or if {@code bytes.length} does not match the
     *         algorithm's expected digest length.
     */
    public ExpectedDigest {
        if (algorithm == null) throw new IllegalArgumentException("algorithm must not be null");
        if (bytes == null) throw new IllegalArgumentException("bytes must not be null");
        int expected = algorithm.digestLengthBytes();
        if (bytes.length != expected) {
            throw new IllegalArgumentException(
                    algorithm + " expects " + expected + " bytes, got " + bytes.length);
        }
        bytes = bytes.clone();
    }

    /** {@return a defensive copy of the expected digest bytes} */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
