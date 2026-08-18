package com.cestats.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stats we infer ourselves by replaying the kill feed. The server never broadcasts any of these,
 * so everything here must be presented as inferred, not measured.
 */
public final class Derived {
    public int openingKills;
    public int openingDeaths;
    public int openingRoundWins;
    public int tradeKills;
    public int mk2;
    public int mk3;
    public int mk4;
    public int mk5;
    public int roundsSurvived;
    public int roundsPlayed;

    /** "1v1", "1v2" ... -> {wins, attempts} */
    public final Map<String, int[]> clutches = new TreeMap<>();
    /** weapon name -> kills */
    public final Map<String, Integer> weapons = new LinkedHashMap<>();
    /** side -> {kills, deaths, rounds} */
    public final Map<Side, int[]> sides = new EnumMap<>(Side.class);

    public void addWeapon(String weapon) {
        weapons.merge(weapon, 1, Integer::sum);
    }

    public int[] side(Side side) {
        return sides.computeIfAbsent(side, s -> new int[3]);
    }

    public void addClutch(String key, boolean won) {
        int[] cur = clutches.computeIfAbsent(key, k -> new int[2]);
        if (won) {
            cur[0]++;
        }
        cur[1]++;
    }

    public void addMultiKill(int n) {
        if (n == 2) {
            mk2++;
        } else if (n == 3) {
            mk3++;
        } else if (n == 4) {
            mk4++;
        } else if (n >= 5) {
            mk5++;
        }
    }
}
