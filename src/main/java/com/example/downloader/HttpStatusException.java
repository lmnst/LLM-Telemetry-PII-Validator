package com.example.downloader;

import java.io.IOException;

public final class HttpStatusException extends IOException {

    private final int statusCode;

    public HttpStatusException(int statusCode) {
        super("HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
