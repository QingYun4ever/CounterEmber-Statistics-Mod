package com.cestats.ping;

/** Local limits for the prototype, kept separate so the eventual multiplayer protocol can reuse them. */
public final class PingPolicy {

    public static final int DEFAULT_MAX_ACTIVE_PINGS = 3;
    public static final long DEFAULT_COOLDOWN_MS = 800L;

    private final int maxActivePings;
    private final long cooldownMs;
    private long lastAcceptedAt = Long.MIN_VALUE;

    public PingPolicy() {
        this(DEFAULT_MAX_ACTIVE_PINGS, DEFAULT_COOLDOWN_MS);
    }

    public PingPolicy(int maxActivePings, long cooldownMs) {
        if (maxActivePings < 1) {
            throw new IllegalArgumentException("max active pings must be positive");
        }
        if (cooldownMs < 0) {
            throw new IllegalArgumentException("cooldown must not be negative");
        }
        this.maxActivePings = maxActivePings;
        this.cooldownMs = cooldownMs;
    }

    /** Checks and accepts a new normal ping. Warning upgrades use {@link #acceptWarning(long)}. */
    public Decision tryAcceptNormal(long now, int activePingCount) {
        if (activePingCount >= maxActivePings) {
            return Decision.LIMIT_REACHED;
        }
        if (cooldownRemainingMs(now) > 0) {
            return Decision.COOLDOWN;
        }
        lastAcceptedAt = now;
        return Decision.ACCEPTED;
    }

    /** A warning replaces the just-created normal marker and therefore bypasses the normal cap. */
    public void acceptWarning(long now) {
        lastAcceptedAt = now;
    }

    public long cooldownRemainingMs(long now) {
        if (lastAcceptedAt == Long.MIN_VALUE || now < lastAcceptedAt) {
            return 0L;
        }
        long elapsed = now - lastAcceptedAt;
        if (elapsed >= cooldownMs) {
            return 0L;
        }
        return cooldownMs - elapsed;
    }

    public int maxActivePings() {
        return maxActivePings;
    }

    public void reset() {
        lastAcceptedAt = Long.MIN_VALUE;
    }

    public enum Decision {
        ACCEPTED,
        COOLDOWN,
        LIMIT_REACHED
    }
}
