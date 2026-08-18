package com.cestats.model;

import java.util.List;

/** A finished round: who won, why, whether the bomb went down, and everything that died. */
public record RoundRecord(int idx, Side winner, String reason, String bombSite, List<KillEvent> kills) {
}
