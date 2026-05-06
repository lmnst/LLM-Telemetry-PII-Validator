package com.example.downloader;

public enum DownloadError {
    RANGES_NOT_SUPPORTED,
    HTTP_ERROR,
    IO_ERROR,
    CANCELLED,
    SIZE_MISMATCH,
    INTEGRITY_FAILURE,
    RESOURCE_CHANGED,
    TIMEOUT
}
