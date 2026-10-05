package com.sheltr.interfaces;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixed-window request counter keyed by client identity.
 *
 * <p>Each key gets {@code limitPerWindow} acquisitions per window, counted from the first
 * acquisition after the previous window ended. The map never holds more than {@code maxKeys}
 * entries. When the map is full and a new key arrives, expired windows are swept, but only once
 * per window length. If no slot is free after that, the new key is refused (fail closed). A live
 * window is never evicted to make room.
 */
public final class FixedWindowLimiter {

    /**
     * Outcome of one acquisition. {@code retryAfterSeconds} is a whole-second hint (at least 1)
     * for a refused call and 0 for an allowed one.
     */
    public record Acquisition(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long startMillis, int count) {
    }

    private static final long NEVER = Long.MIN_VALUE;

    private final int limitPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final int maxKeys;

    // Every access goes through `lock`; the HashMap needs no concurrent-map semantics of its own.
    private final Object lock = new Object();
    private final Map<String, Window> windows = new HashMap<>();
    private long lastSweepMillis = NEVER;

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

    /** Records one acquisition for {@code key}. Returns false when the key is refused. */
    public boolean tryAcquire(String key) {
        return acquire(key).allowed();
    }

    /**
     * Records one acquisition and, when refused, the retry hint. The hint is computed inside the
     * same lock as the decision, so it describes the state that produced the refusal.
     */
    public Acquisition acquire(String key) {
        long now = clock.millis();
        // One lock for the whole call. The size cap, the sweep timestamp and the per-key window
        // update must change together: with per-key locking alone, two new keys could both pass
        // the size check and push the map past maxKeys, and a sweep could race an insert.
        synchronized (lock) {
            if (!windows.containsKey(key) && windows.size() >= maxKeys) {
                if (sweepDue(now)) {
                    windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
                    lastSweepMillis = now;
                }
                if (windows.size() >= maxKeys) {
                    return new Acquisition(false, secondsUntil(retryAtCapMillis(now), now));
                }
            }
            Window current = windows.get(key);
            if (current == null || now - current.startMillis() >= windowMillis) {
                windows.put(key, new Window(now, 1));
                return new Acquisition(true, 0L);
            }
            if (current.count() < limitPerWindow) {
                windows.put(key, new Window(current.startMillis(), current.count() + 1));
                return new Acquisition(true, 0L);
            }
            return new Acquisition(false, secondsUntil(current.startMillis() + windowMillis, now));
        }
    }

    int size() {
        synchronized (lock) {
            return windows.size();
        }
    }

    private boolean sweepDue(long now) {
        return lastSweepMillis == NEVER || now - lastSweepMillis >= windowMillis;
    }

    /**
     * When a slot can next free up: the earliest live window end, but not before the next sweep
     * is allowed, since expired windows are only removed by a sweep.
     */
    private long retryAtCapMillis(long now) {
        long earliestEnd = Long.MAX_VALUE;
        for (Window w : windows.values()) {
            earliestEnd = Math.min(earliestEnd, w.startMillis() + windowMillis);
        }
        return Math.max(earliestEnd, lastSweepMillis + windowMillis);
    }

    private static long secondsUntil(long endMillis, long now) {
        return Math.max(1L, (endMillis - now + 999L) / 1000L);
    }
}
