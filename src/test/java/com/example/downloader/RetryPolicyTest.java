package com.example.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {

    private static final DownloaderOptions OPTS_3 = DownloaderOptions.builder()
            .maxRetriesPerChunk(3)
            .retryBaseDelay(Duration.ofMillis(200))
            .build();

    private static final Random FIXED_SEED = new Random(42);

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void retriesOnRetryableHttpStatuses(int code) {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        Optional<Duration> result = policy.evaluate(0, new RetryPolicy.Trigger.HttpStatus(code));
        assertThat(result).isPresent();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 410, 422})
    void noRetryOnNonRetryable4xx(int code) {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        Optional<Duration> result = policy.evaluate(0, new RetryPolicy.Trigger.HttpStatus(code));
        assertThat(result).isEmpty();
    }

    @Test
    void retriesOnIoFailure() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        Optional<Duration> result = policy.evaluate(0, new RetryPolicy.Trigger.IoFailure(new IOException("reset")));
        assertThat(result).isPresent();
    }

    @Test
    void retriesOnTimeout() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        Optional<Duration> result = policy.evaluate(0, new RetryPolicy.Trigger.Timeout());
        assertThat(result).isPresent();
    }

    @Test
    void stopsAfterMaxRetries() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        // attempt 3 == maxRetries → no more retries
        assertThat(policy.evaluate(3, new RetryPolicy.Trigger.Timeout())).isEmpty();
        assertThat(policy.evaluate(10, new RetryPolicy.Trigger.Timeout())).isEmpty();
    }

    @Test
    void maxRetriesZeroMeansNeverRetry() {
        DownloaderOptions noRetry = DownloaderOptions.builder().maxRetriesPerChunk(0).build();
        RetryPolicy policy = new RetryPolicy(noRetry, new Random(0));
        assertThat(policy.evaluate(0, new RetryPolicy.Trigger.Timeout())).isEmpty();
    }

    @Test
    void honorsRetryAfterHint() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        Duration hint = Duration.ofSeconds(5);
        Optional<Duration> result = policy.evaluate(0,
                new RetryPolicy.Trigger.HttpStatus(429, hint));
        assertThat(result).hasValue(hint);
    }

    @Test
    void delayIsNonNegative() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, FIXED_SEED);
        for (int attempt = 0; attempt < 3; attempt++) {
            Duration d = policy.evaluate(attempt, new RetryPolicy.Trigger.Timeout()).orElseThrow();
            assertThat(d).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @Test
    void delayDoesNotExceedCap() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(0));
        Duration maxAllowed = Duration.ofSeconds(30);
        for (int attempt = 0; attempt < 3; attempt++) {
            Duration d = policy.evaluate(attempt, new RetryPolicy.Trigger.Timeout()).orElseThrow();
            assertThat(d).isLessThanOrEqualTo(maxAllowed);
        }
    }

    @Test
    void zeroBaseDelayProducesZeroDelay() {
        DownloaderOptions zeroDelay = DownloaderOptions.builder().retryBaseDelay(Duration.ZERO).build();
        RetryPolicy policy = new RetryPolicy(zeroDelay, new Random(0));
        Optional<Duration> result = policy.evaluate(0, new RetryPolicy.Trigger.Timeout());
        assertThat(result).hasValue(Duration.ZERO);
    }

    @Test
    void noRetryAfterHintZeroUsesBackoff() {
        RetryPolicy policy = new RetryPolicy(OPTS_3, new Random(1));
        // retryAfterHint == ZERO → falls through to backoff calculation
        Optional<Duration> result = policy.evaluate(0,
                new RetryPolicy.Trigger.HttpStatus(429, Duration.ZERO));
        assertThat(result).isPresent();
        // duration should be in [0, 200ms] for attempt 0 with 200ms base
        assertThat(result.get()).isBetween(Duration.ZERO, Duration.ofMillis(200));
    }
}
