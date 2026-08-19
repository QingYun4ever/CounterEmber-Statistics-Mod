package com.cestats.ping;

/**
 * Classifies middle-clicks without depending on Minecraft client state.
 *
 * <p>The first click is held briefly so a quick second click can replace it with a warning ping.
 * This prevents a double-click from showing an ordinary ping followed by a warning ping.</p>
 */
public final class PingClickDetector {

    public static final long DEFAULT_DOUBLE_CLICK_WINDOW_MS = 280L;

    private final long doubleClickWindowMs;
    private long pendingClickAt = Long.MIN_VALUE;

    public PingClickDetector() {
        this(DEFAULT_DOUBLE_CLICK_WINDOW_MS);
    }

    public PingClickDetector(long doubleClickWindowMs) {
        if (doubleClickWindowMs < 1) {
            throw new IllegalArgumentException("double-click window must be positive");
        }
        this.doubleClickWindowMs = doubleClickWindowMs;
    }

    /** Registers a click. The first click is pending; the second click becomes a warning. */
    public ClickResult registerClick(long now) {
        if (pendingClickAt != Long.MIN_VALUE
                && now >= pendingClickAt
                && now - pendingClickAt <= doubleClickWindowMs) {
            pendingClickAt = Long.MIN_VALUE;
            return ClickResult.WARNING;
        }

        pendingClickAt = now;
        return ClickResult.PENDING;
    }

    /** Returns whether the pending first click has waited long enough to become normal. */
    public boolean isPendingExpired(long now) {
        return pendingClickAt != Long.MIN_VALUE
                && now >= pendingClickAt
                && now - pendingClickAt > doubleClickWindowMs;
    }

    public void commitPending() {
        pendingClickAt = Long.MIN_VALUE;
    }

    public void reset() {
        pendingClickAt = Long.MIN_VALUE;
    }

    public enum ClickResult {
        PENDING,
        WARNING
    }
}
