package io.github.lmnst.downloader;

/**
 * Receives {@link ProgressEvent}s during a download. The downloader invokes
 * {@code onProgress} from a single virtual dispatcher thread, so listener
 * implementations need not be thread-safe with respect to each other.
 *
 * <p>Exceptions thrown from {@code onProgress} are caught and the first one
 * is logged once to {@code System.err} with the event class name; subsequent
 * exceptions are dropped silently. The download itself is not interrupted.
 *
 * @see ProgressEvent
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * Receives a single progress event.
     *
     * @param event the event to handle
     */
    void onProgress(ProgressEvent event);

    /**
     * No-op listener, the default. The downloader recognises this instance
     * specifically and skips the dispatcher thread + queue allocation entirely.
     */
    ProgressListener NO_OP = event -> {};
}
