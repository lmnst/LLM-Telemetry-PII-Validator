package com.example.downloader;

public record ExpectedDigest(Algorithm algorithm, byte[] bytes) {

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

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
