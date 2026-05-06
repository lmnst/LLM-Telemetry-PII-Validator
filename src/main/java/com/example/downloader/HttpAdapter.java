package com.example.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface HttpAdapter {

    HeadResponse head(URI uri) throws IOException, InterruptedException;

    /**
     * Executes a GET for the given range and streams the response body to sink
     * in bounded buffers. Returns the HTTP status, actual bytes written, the
     * Content-Range header value (if any), and an `ifRangeMismatch` flag set when
     * `ifRange` was non-null AND the server returned 200 (i.e. it ignored the
     * range because the validator did not match — the resource has changed).
     *
     * @param range    null = no Range header (single-stream)
     * @param ifRange  null = no If-Range header; otherwise the validator value
     *                 (an ETag, optionally weak, or an HTTP-date) to send.
     */
    GetResponse get(URI uri, ByteRange range, String ifRange,
                    Consumer<ByteBuffer> sink, CancelToken cancel)
            throws IOException, InterruptedException;

    record HeadResponse(int status, long contentLength, boolean acceptRanges,
                        String etag, String lastModified) {}

    record GetResponse(int status, long bytesWritten, String contentRangeHeader,
                       boolean ifRangeMismatch) {}
}
