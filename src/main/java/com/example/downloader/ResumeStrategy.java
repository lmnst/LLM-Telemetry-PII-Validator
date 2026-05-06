package com.example.downloader;

/**
 * How an existing partial download (the {@code <dest>.part} file and its
 * {@code <dest>.part.json} sidecar) is treated when {@link Downloader#download}
 * is called.
 */
@org.jetbrains.annotations.ApiStatus.Experimental
public enum ResumeStrategy {

    /**
     * Always start from scratch. Any existing {@code .part} / {@code .part.json}
     * files at the destination are deleted before the download begins.
     * The default.
     */
    FRESH,

    /**
     * Reuse an existing {@code .part} / {@code .part.json} sidecar if one is
     * found and its recorded ETag, contentLength, and chunkSize all match the
     * current HEAD. On any mismatch the download fails fast with
     * {@link DownloadError#RESOURCE_CHANGED} so the caller can decide whether
     * to delete the sidecar and retry {@link #FRESH}.
     */
    RESUME_IF_VALID
}
