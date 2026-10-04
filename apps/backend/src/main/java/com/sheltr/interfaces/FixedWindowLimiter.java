package com.sheltr.interfaces;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window request counter keyed by client identity.
 *
 * <p>Each key gets {@code limitPerWindow} acquisitions per window, counted from the first
 * acquisition after the previous window ended. The map never holds more than {@code maxKeys}
 * entries: when a new key arrives at the cap, windows that have already expired are dropped
 * first, and if every tracked window is still live the oldest one is dropped. Each key's update
 * is atomic through {@link ConcurrentHashMap#compute}; inserts and evictions are serialised so the
 * size bound holds under concurrent callers.
 */
public final class FixedWindowLimiter {

    private record Window(long startMillis, int count) {
    }

    private final int limitPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FixedWindowLimiter(int limitPerWindow, Duration window, Clock clock, int maxKeys) {
        if (limitPerWindow < 1) {
            throw new IllegalArgumentException("limitPerWindow must be at least 1");
        }
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException("window must be at least 1 millisecond");
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys must be at least 1");
        }
        this.limitPerWindow = limitPerWindow;
        this.windowMillis = window.toMillis();
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    /** Records one acquisition for {@code key}. Returns false when the key is over its limit. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        synchronized (lock) {
            if (!windows.containsKey(key) && windows.size() >= maxKeys) {
                evictStale(now);
                if (windows.size() >= maxKeys) {
                    evictOldest();
                }
            }
            boolean[] allowed = {false};
            windows.compute(key, (k, w) -> {
                if (w == null || now - w.startMillis() >= windowMillis) {
                    allowed[0] = true;
                    return new Window(now, 1);
                }
                if (w.count() < limitPerWindow) {
                    allowed[0] = true;
                    return new Window(w.startMillis(), w.count() + 1);
                }
                return w;
            });
            return allowed[0];
        }
    }

    /** Whole seconds until {@code key}'s current window ends, at least 1. */
    public long retryAfterSeconds(String key) {
        Window w = windows.get(key);
        if (w == null) {
            return 1L;
        }
        long remainingMillis = w.startMillis() + windowMillis - clock.millis();
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    int size() {
        return windows.size();
    }

    private void evictStale(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMillis);
    }

    private void evictOldest() {
        windows.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().startMillis(), b.getValue().startMillis()))
                .ifPresent(e -> windows.remove(e.getKey()));
    }
}
