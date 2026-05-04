package com.example.downloader;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface HttpAdapter {

    HeadResponse head(URI uri) throws IOException, InterruptedException;

    /**
     * Executes a GET for the given range (null = no Range header) and streams
     * the response body to sink in bounded buffers. Returns the HTTP status and
     * actual byte count written to sink.
     */
    GetResponse get(URI uri, ByteRange range, Consumer<ByteBuffer> sink, CancelToken cancel)
            throws IOException, InterruptedException;

    record HeadResponse(int status, long contentLength, boolean acceptRanges, String etag) {}

    record GetResponse(int status, long bytesWritten, String contentRangeHeader) {}
}
