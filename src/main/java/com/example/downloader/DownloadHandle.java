package com.example.downloader;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DownloadHandle {

    public enum State { RUNNING, DONE, CANCELLED }

    private final Future<DownloadResult> future;
    private final CancelToken cancelToken;
    private volatile State state = State.RUNNING;

    DownloadHandle(Future<DownloadResult> future, CancelToken cancelToken) {
        this.future = future;
        this.cancelToken = cancelToken;
    }

    public void cancel() {
        cancelToken.cancel();
        state = State.CANCELLED;
        future.cancel(true);
    }

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

    public State state() { return state; }
}
