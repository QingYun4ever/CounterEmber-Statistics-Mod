package com.cestats.model;

import java.util.List;

/**
 * A complete, uploadable match.
 *
 * <p>{@code ctScore}/{@code tScore} are round wins per TEAM, not per side: sides swap at halftime,
 * so tallying the "反恐精英 获胜" broadcasts directly produces a score that can contradict the
 * announced winner.
 *
 * <p>{@code complete} means we observed at least as many kill-feed entries as the table reports.
 * The table drops players who left, so the feed can legitimately hold more — but never fewer,
 * unless we missed rounds.
 */
public record MatchRecord(String matchId, String server, String uploader, long endedAt,
                          Winner winner, String mvp, int ctScore, int tScore,
                          int roundsObserved, boolean complete,
                          List<PlayerRecord> players, List<RoundRecord> rounds) {

    public PlayerRecord player(String name) {
        for (PlayerRecord p : players) {
            if (p.stat().name().equals(name)) {
                return p;
            }
        }
        return null;
    }
}
