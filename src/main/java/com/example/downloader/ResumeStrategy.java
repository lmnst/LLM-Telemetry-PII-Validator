package com.example.downloader;

public enum ResumeStrategy {

    /**
     * Always start from scratch. Any existing .part / .part.json files at the
     * destination are deleted before the download begins. The default.
     */
    FRESH,

    /**
     * Reuse an existing .part / .part.json sidecar if one is found and its
     * recorded ETag, contentLength, and chunkSize all match the current HEAD.
     * On any mismatch the download fails fast with RESOURCE_CHANGED so the
     * caller can decide whether to delete the sidecar and retry FRESH.
     */
    RESUME_IF_VALID
}
