package io.github.lmnst.downloader;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A handle to an asynchronous download started by
 * {@link Downloader#downloadAsync(java.net.URI, java.nio.file.Path)}.
 * Lets the caller poll for completion, wait with a timeout, or cancel.
 * Cancellation is cooperative: it sets a flag observed by the worker thread
 * and interrupts the future; the worker still runs its cleanup before the
 * task settles.
 */
public final class DownloadHandle {

    /** Lifecycle state of a {@link DownloadHandle}. */
    public enum State {
        /** Worker is still running; {@link #join()} will block. */
        RUNNING,
        /** Worker completed normally (success or failure); {@link #join()} returns immediately. */
        DONE,
        /** Cancellation has been requested via {@link #cancel()}. */
        CANCELLED
    }

    private final Future<DownloadResult> future;
    private final CancelToken cancelToken;
    private volatile State state = State.RUNNING;

    DownloadHandle(Future<DownloadResult> future, CancelToken cancelToken) {
        this.future = future;
        this.cancelToken = cancelToken;
    }

    /**
     * Requests cancellation of the running download. Sets the cooperative
     * cancel flag, marks the handle CANCELLED, and interrupts the worker.
     * Idempotent.
     */
    public void cancel() {
        cancelToken.cancel();
        state = State.CANCELLED;
        future.cancel(true);
    }

    /**
     * Blocks until the download settles and returns the result. Cancellation
     * is reported as a {@link DownloadResult.Failure} with
     * {@link DownloadError#CANCELLED}; an unexpected runtime failure is
     * reported as {@link DownloadError#IO_ERROR}.
     *
     * @return the result of the download (never null)
     * @throws InterruptedException if the calling thread is interrupted
     */
    public DownloadResult join() throws InterruptedException {
        try {
            DownloadResult result = future.get();
            if (state != State.CANCELLED) state = State.DONE;
            return result;
        } catch (CancellationException e) {
            return new DownloadResult.Failure(DownloadError.CANCELLED, e);
        } catch (ExecutionException e) {
            return new DownloadResult.Failure(DownloadError.IO_ERROR, e.getCause());
        }
    }

    /**
     * Like {@link #join()} but with a timeout. A timeout maps to
     * {@link DownloadError#TIMEOUT} on the returned Failure; the worker
     * keeps running in the background.
     *
     * @param timeout how long to wait
     * @return       the result of the download (never null)
     * @throws InterruptedException if the calling thread is interrupted
     */
    public DownloadResult joinWithTimeout(Duration timeout) throws InterruptedException {
        try {
            DownloadResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (state != State.CANCELLED) state = State.DONE;
            return result;
        } catch (CancellationException e) {
            return new DownloadResult.Failure(DownloadError.CANCELLED, e);
        } catch (ExecutionException e) {
            return new DownloadResult.Failure(DownloadError.IO_ERROR, e.getCause());
        } catch (TimeoutException e) {
            return new DownloadResult.Failure(DownloadError.TIMEOUT, e);
        }
    }

    /** {@return the current lifecycle state} */
    public State state() { return state; }
}
