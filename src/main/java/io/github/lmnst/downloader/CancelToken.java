package io.github.lmnst.downloader;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation flag passed to {@link HttpAdapter#get}. The
 * downloader sets the flag from {@link DownloadHandle#cancel()}; adapter
 * implementations should poll {@link #isCancelled()} between buffer reads
 * and abort with {@link InterruptedException} when it returns true.
 */
public final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Constructs a fresh, not-yet-cancelled token. */
    public CancelToken() {}

    /** Sets the cancelled flag. Idempotent. */
    public void cancel() { cancelled.set(true); }

    /** {@return true once {@link #cancel()} has been called} */
    public boolean isCancelled() { return cancelled.get(); }
}
