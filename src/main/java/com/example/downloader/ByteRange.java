package com.example.downloader;

/**
 * Inclusive byte range for an HTTP {@code Range} request. Per RFC 9110,
 * {@code Range: bytes=a-b} is inclusive on both ends, so
 * {@link #lastByte()} returns {@code offset + length - 1}.
 *
 * @param offset starting byte (zero-based, must be non-negative)
 * @param length number of bytes (must be positive)
 */
public record ByteRange(long offset, long length) {

    /**
     * Validates {@code offset} and {@code length}.
     *
     * @throws IllegalArgumentException if {@code offset} is negative or
     *         {@code length} is non-positive.
     */
    public ByteRange {
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (length <= 0) throw new IllegalArgumentException("length must be > 0");
    }

    /** {@return the last byte of the range, inclusive} */
    public long lastByte() {
        return offset + length - 1;
    }

    /** {@return the HTTP {@code Range} header value, e.g. {@code bytes=0-1023}} */
    public String httpHeaderValue() {
        return "bytes=" + offset + "-" + lastByte();
    }
}
