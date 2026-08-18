package com.cestats.model;

/**
 * One entry of the kill feed.
 *
 * <p>{@code killer} and {@code weapon} are null for a non-kill death, which the server reports
 * as a bare "&lt;name&gt; 死亡" (fall damage or void). The two flags are filled in later, while
 * replaying the round in {@link com.cestats.derive.DerivedCalculator}.
 */
public final class KillEvent {
    public final int seq;
    public final long ts;
    public final String killer;
    public final Side killerSide;
    public final String weapon;
    public final String victim;
    public final Side victimSide;

    public boolean opening;
    public boolean trade;

    public KillEvent(int seq, long ts, String killer, Side killerSide, String weapon,
                     String victim, Side victimSide) {
        this.seq = seq;
        this.ts = ts;
        this.killer = killer;
        this.killerSide = killerSide;
        this.weapon = weapon;
        this.victim = victim;
        this.victimSide = victimSide;
    }
}
