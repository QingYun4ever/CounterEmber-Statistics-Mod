package com.cestats.match;

import com.cestats.derive.DerivedCalculator;
import com.cestats.model.Derived;
import com.cestats.model.KillEvent;
import com.cestats.model.MatchRecord;
import com.cestats.model.PlayerRecord;
import com.cestats.model.RoundRecord;
import com.cestats.model.Side;
import com.cestats.model.StatLine;
import com.cestats.model.Winner;
import com.cestats.parse.ChatEvent;
import com.cestats.parse.ChatPatterns;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Feeds on raw system chat messages and emits a finished {@link MatchRecord} once the server
 * prints the end-of-match table followed by the result line.
 *
 * <p>No Minecraft dependency, so the whole state machine is unit testable against log lines.
 */
public final class MatchTracker {

    /** How long to wait for the "比赛结束！" line that normally follows the stats table. */
    public static final long RESULT_TIMEOUT_MS = 5000L;

    /**
     * Backstop for a missing match-start marker: this long without a single combat message means
     * whatever we buffered belongs to an older match. Real matches run a round every 1-2 minutes;
     * the longest observed within-match gap was 6.6 minutes.
     */
    public static final long CONTEXT_GAP_MS = 10L * 60L * 1000L;

    private final Consumer<MatchRecord> onMatch;
    private final Consumer<ChatEvent> onEvent;

    private String server = "unknown";
    private String uploader = "unknown";

    private List<RoundRecord> rounds = new ArrayList<>();
    private List<KillEvent> curKills = new ArrayList<>();
    private String curBomb;
    private int seq;
    private long lastCombatTs;

    private List<StatLine> pending;
    private long pendingTs;

    public MatchTracker(Consumer<MatchRecord> onMatch) {
        this(onMatch, ignored -> {
        });
    }

    /**
     * Creates a tracker with an optional observer for parsed lifecycle events. The observer is
     * deliberately separate from {@code onMatch}: integrations such as replay recording can react
     * to room/combat boundaries without changing the match data model.
     */
    public MatchTracker(Consumer<MatchRecord> onMatch, Consumer<ChatEvent> onEvent) {
        this.onMatch = onMatch;
        this.onEvent = onEvent;
    }

    public void setContext(String server, String uploader) {
        this.server = server;
        this.uploader = uploader;
    }

    public String uploader() {
        return uploader;
    }

    /** Call with every system chat message. {@code ts} is epoch millis. */
    public void accept(String content, long ts) {
        tick(ts);
        ChatEvent event = ChatPatterns.parse(content, uploader);
        if (event == null) {
            return;
        }
        onEvent.accept(event);

        boolean combat = event instanceof ChatEvent.Kill || event instanceof ChatEvent.Death
                || event instanceof ChatEvent.Bomb || event instanceof ChatEvent.RoundEnd;
        if (combat) {
            if (lastCombatTs != 0 && ts - lastCombatTs > CONTEXT_GAP_MS) {
                clearRounds();
            }
            lastCombatTs = ts;
        }

        switch (event) {
            case ChatEvent.Kill kill -> curKills.add(new KillEvent(seq++, ts, kill.killer(),
                    kill.killerSide(), kill.weapon(), kill.victim(), kill.victimSide()));
            case ChatEvent.Death death -> curKills.add(
                    new KillEvent(seq++, ts, null, null, null, death.victim(), null));
            case ChatEvent.Bomb bomb -> curBomb = bomb.site();
            case ChatEvent.RoundEnd end -> {
                rounds.add(new RoundRecord(rounds.size(), end.winner(), end.reason(), curBomb,
                        curKills));
                curKills = new ArrayList<>();
                curBomb = null;
            }
            case ChatEvent.Stats stats -> {
                pending = stats.players();
                pendingTs = ts;
            }
            case ChatEvent.Result result -> finalizeMatch(result.winner());
            case ChatEvent.ContextReset ignored -> clearRounds();
        }
    }

    /**
     * Flushes a stats table that never got its result line. Safe to call from a client tick.
     */
    public void tick(long now) {
        if (pending != null && now - pendingTs > RESULT_TIMEOUT_MS) {
            finalizeMatch(Winner.UNKNOWN);
        }
    }

    /** Drops everything buffered — used when leaving the server mid-match. */
    public void reset() {
        clearRounds();
        lastCombatTs = 0;
        pending = null;
    }

    private void clearRounds() {
        rounds = new ArrayList<>();
        curKills = new ArrayList<>();
        curBomb = null;
        seq = 0;
    }

    private void finalizeMatch(Winner winner) {
        if (pending == null) {
            return;
        }
        List<StatLine> roster = pending;
        List<RoundRecord> finished = rounds;
        long endedAt = pendingTs;
        reset();

        List<Map<String, Side>> sides = DerivedCalculator.assignSides(finished, roster);
        Map<String, Derived> derived = DerivedCalculator.compute(finished, roster, sides);
        int[] score = DerivedCalculator.teamScore(finished, roster, sides);

        int observedKills = 0;
        for (RoundRecord round : finished) {
            for (KillEvent k : round.kills()) {
                if (k.killer != null) {
                    observedKills++;
                }
            }
        }
        int tableKills = 0;
        String mvp = null;
        for (StatLine p : roster) {
            tableKills += p.kills();
            if (p.mvp()) {
                mvp = p.name();
            }
        }

        List<PlayerRecord> players = new ArrayList<>(roster.size());
        for (StatLine p : roster) {
            players.add(new PlayerRecord(p, derived.getOrDefault(p.name(), new Derived())));
        }

        MatchRecord match = new MatchRecord(
                MatchId.compute(server, roster), server, uploader, endedAt, winner, mvp,
                score[0], score[1], finished.size(),
                // The table drops players who left, so the feed can legitimately hold MORE kills
                // than the table — but never fewer unless we missed rounds.
                !finished.isEmpty() && observedKills >= tableKills,
                players, finished);

        onMatch.accept(match);
    }
}
