package com.example.downloader;

public enum Algorithm {
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
