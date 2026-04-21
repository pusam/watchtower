package com.watchtower.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginRateLimiter();
    }

    @Test
    void notBlocked_whenNoFailures() {
        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void locksOutAfterThresholdReached() {
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();
    }

    @Test
    void lockoutIsPerIp() {
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();
        assertThat(limiter.isBlocked("5.6.7.8")).isFalse();
    }

    @Test
    void success_clearsCounterWhenNotLockedOut() {
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordSuccess("1.2.3.4");
        assertThat(limiter.trackedSize()).isZero();
    }

    @Test
    void success_doesNotClearActiveLockout() {
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        limiter.recordSuccess("1.2.3.4");
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();
    }

    @Test
    void nullIpIsIgnored() {
        limiter.recordFailure(null);
        assertThat(limiter.isBlocked(null)).isFalse();
        assertThat(limiter.trackedSize()).isZero();
    }
}
