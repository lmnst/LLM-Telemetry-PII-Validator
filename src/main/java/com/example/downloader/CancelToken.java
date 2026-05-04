package com.example.downloader;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() { cancelled.set(true); }

    public boolean isCancelled() { return cancelled.get(); }
}
