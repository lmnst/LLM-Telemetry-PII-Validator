package com.example.downloader;

public record ByteRange(long offset, long length) {

    public ByteRange {
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (length <= 0) throw new IllegalArgumentException("length must be > 0");
    }

    public long lastByte() {
        return offset + length - 1;
    }

    public String httpHeaderValue() {
        return "bytes=" + offset + "-" + lastByte();
    }
}
