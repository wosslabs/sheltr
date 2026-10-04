package com.sheltr.interfaces;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedWindowLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration d) {
            millis += d.toMillis();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    @Test
    void allowsLimitPerKeyThenRejectsAndIsolatesKeys() {
        var limiter = new FixedWindowLimiter(10, WINDOW, new MutableClock(0), 100);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("a")).isTrue();
        }
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    void allowsKeyAgainAfterWindowPasses() {
        var clock = new MutableClock(0);
        var limiter = new FixedWindowLimiter(10, WINDOW, clock, 100);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a")).isFalse();

        clock.advance(WINDOW.plusMillis(1));

        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    void retryAfterReportsWholeSecondsUntilWindowEnds() {
        var clock = new MutableClock(0);
        var limiter = new FixedWindowLimiter(1, WINDOW, clock, 100);
        limiter.tryAcquire("a");
        clock.advance(Duration.ofMillis(20_500));

        assertThat(limiter.retryAfterSeconds("a")).isEqualTo(40L);
    }

    @Test
    void mapNeverExceedsMaxKeysAndStaleEntriesAreEvicted() {
        var clock = new MutableClock(0);
        var limiter = new FixedWindowLimiter(10, WINDOW, clock, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        assertThat(limiter.size()).isEqualTo(2);

        clock.advance(WINDOW);
        assertThat(limiter.tryAcquire("c")).isTrue();

        assertThat(limiter.size()).isLessThanOrEqualTo(2);
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void mapStaysAtCapWhenEveryTrackedWindowIsStillLive() {
        var clock = new MutableClock(0);
        var limiter = new FixedWindowLimiter(10, WINDOW, clock, 2);
        limiter.tryAcquire("a");
        clock.advance(Duration.ofSeconds(1));
        limiter.tryAcquire("b");
        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.tryAcquire("c")).isTrue();

        assertThat(limiter.size()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        var clock = new MutableClock(0);
        assertThatThrownBy(() -> new FixedWindowLimiter(0, WINDOW, clock, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowLimiter(1, Duration.ZERO, clock, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowLimiter(1, WINDOW, clock, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
