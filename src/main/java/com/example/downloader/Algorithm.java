package com.example.downloader;

/**
 * Hash algorithms supported by {@link ExpectedDigest}. Single-member by design
 *, adding a new algorithm requires only a new enum constant and a
 * {@code digestLengthBytes()} arm; consumers that pattern-match the enum
 * still compile.
 */
public enum Algorithm {

    /** SHA-256, 32-byte digest. */
    SHA_256;

    String javaName() {
        return switch (this) {
            case SHA_256 -> "SHA-256";
        };
    }

    int digestLengthBytes() {
        return switch (this) {
            case SHA_256 -> 32;
        };
    }
}
