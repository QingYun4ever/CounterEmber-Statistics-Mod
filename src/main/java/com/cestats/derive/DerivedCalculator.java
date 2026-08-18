package com.cestats.derive;

import com.cestats.model.Derived;
import com.cestats.model.KillEvent;
import com.cestats.model.RoundRecord;
import com.cestats.model.Side;
import com.cestats.model.StatLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recovers the stats the server never broadcasts by replaying each round from the kill feed:
 * opening duels, multi-kills, clutches, trade kills, survival and weapon usage.
 *
 * <p>Java twin of the same section in {@code web/src/lib/parse.ts}.
 */
public final class DerivedCalculator {

    /**
     * Largest roster a single side has ever shown in a stats table. Used as a sanity gate: if a
     * round's replay puts more than this on one side, our side assignment drifted and the clutch
     * numbers for that round cannot be trusted.
     */
    public static final int MAX_TEAM_SIZE = 5;

    /** A kill only counts as a trade if it lands this soon after the teammate died. */
    public static final long TRADE_WINDOW_MS = 5000L;

    private DerivedCalculator() {
    }

    /**
     * Works out which side every player was on in every round.
     *
     * <p>Sides flip at halftime, so the final team in the stats table is only correct for the
     * second half. The kill feed tags each combatant with their side at that moment, so those are
     * ground truth; a player's quiet rounds are filled from their nearest known round.
     */
    public static List<Map<String, Side>> assignSides(List<RoundRecord> rounds, List<StatLine> roster) {
        List<Map<String, Side>> observed = new ArrayList<>(rounds.size());
        for (RoundRecord round : rounds) {
            Map<String, Side> m = new HashMap<>();
            for (KillEvent k : round.kills()) {
                if (k.killer != null && k.killerSide != null) {
                    m.put(k.killer, k.killerSide);
                }
                if (k.victimSide != null) {
                    m.put(k.victim, k.victimSide);
                }
            }
            observed.add(m);
        }

        Map<String, Side> rosterSide = new LinkedHashMap<>();
        for (StatLine p : roster) {
            rosterSide.put(p.name(), p.team());
        }
        Set<String> names = new LinkedHashSet<>(rosterSide.keySet());
        for (Map<String, Side> m : observed) {
            names.addAll(m.keySet());
        }

        List<Map<String, Side>> out = new ArrayList<>(rounds.size());
        for (int i = 0; i < rounds.size(); i++) {
            Map<String, Side> result = new HashMap<>();
            for (String name : names) {
                Side side = observed.get(i).get(name);
                for (int d = 1; side == null && d < rounds.size(); d++) {
                    if (i - d >= 0) {
                        side = observed.get(i - d).get(name);
                    }
                    if (side == null && i + d < rounds.size()) {
                        side = observed.get(i + d).get(name);
                    }
                }
                if (side == null) {
                    side = rosterSide.get(name);
                }
                if (side != null) {
                    result.put(name, side);
                }
            }
            out.add(result);
        }
        return out;
    }

    /** First and last round in which each name appeared, so departed players stop being counted. */
    private static Map<String, int[]> presenceWindow(List<RoundRecord> rounds) {
        Map<String, int[]> seen = new HashMap<>();
        for (int i = 0; i < rounds.size(); i++) {
            for (KillEvent k : rounds.get(i).kills()) {
                for (String name : new String[] {k.killer, k.victim}) {
                    if (name == null) {
                        continue;
                    }
                    int[] cur = seen.get(name);
                    if (cur == null) {
                        seen.put(name, new int[] {i, i});
                    } else {
                        cur[1] = i;
                    }
                }
            }
        }
        return seen;
    }

    /** Round wins per TEAM (see {@link com.cestats.model.MatchRecord}); index 0 = CT, 1 = T. */
    public static int[] teamScore(List<RoundRecord> rounds, List<StatLine> roster,
                                  List<Map<String, Side>> sides) {
        Map<String, Side> finalTeam = new HashMap<>();
        for (StatLine p : roster) {
            finalTeam.put(p.name(), p.team());
        }

        int[] score = new int[2];
        for (int i = 0; i < rounds.size(); i++) {
            RoundRecord round = rounds.get(i);
            int ct = 0;
            int t = 0;
            for (Map.Entry<String, Side> e : sides.get(i).entrySet()) {
                if (e.getValue() != round.winner()) {
                    continue;
                }
                Side team = finalTeam.get(e.getKey());
                if (team == Side.CT) {
                    ct++;
                } else if (team == Side.T) {
                    t++;
                }
            }
            if (ct > t) {
                score[0]++;
            } else if (t > ct) {
                score[1]++;
            }
        }
        return score;
    }

    /**
     * Mutates {@code opening} / {@code trade} on the kill events in place so the uploaded timeline
     * carries them, and returns per-player derived stats keyed by name.
     */
    public static Map<String, Derived> compute(List<RoundRecord> rounds, List<StatLine> roster,
                                               List<Map<String, Side>> sides) {
        Map<String, Derived> acc = new LinkedHashMap<>();
        for (StatLine p : roster) {
            acc.put(p.name(), new Derived());
        }

        Set<String> rosterNames = new HashSet<>(acc.keySet());
        Map<String, int[]> window = presenceWindow(rounds);

        for (int ri = 0; ri < rounds.size(); ri++) {
            RoundRecord round = rounds.get(ri);
            Map<String, Side> sideOf = sides.get(ri);

            // Everyone still present at the end, plus anyone who left mid-match but was
            // still active during this round.
            Set<String> participants = new LinkedHashSet<>();
            for (String name : rosterNames) {
                if (sideOf.containsKey(name)) {
                    participants.add(name);
                }
            }
            for (Map.Entry<String, int[]> e : window.entrySet()) {
                String name = e.getKey();
                int[] span = e.getValue();
                if (!rosterNames.contains(name) && ri >= span[0] && ri <= span[1]
                        && sideOf.containsKey(name)) {
                    participants.add(name);
                }
            }

            Map<Side, Set<String>> alive = new HashMap<>();
            alive.put(Side.CT, new LinkedHashSet<>());
            alive.put(Side.T, new LinkedHashSet<>());
            for (String name : participants) {
                alive.get(sideOf.get(name)).add(name);
            }
            int startCt = alive.get(Side.CT).size();
            int startT = alive.get(Side.T).size();
            boolean clutchesTrustworthy = startCt <= MAX_TEAM_SIZE && startT <= MAX_TEAM_SIZE;

            Map<String, Integer> killsThisRound = new HashMap<>();
            Set<String> died = new HashSet<>();
            Set<Side> clutched = new HashSet<>();
            boolean openingDone = false;

            for (KillEvent k : round.kills()) {
                k.opening = false;
                k.trade = false;

                if (k.killer != null) {
                    Side killerSide = sideOf.getOrDefault(k.killer, k.killerSide);

                    if (!openingDone) {
                        openingDone = true;
                        k.opening = true;
                        Derived kd = acc.get(k.killer);
                        if (kd != null) {
                            kd.openingKills++;
                            if (killerSide == round.winner()) {
                                kd.openingRoundWins++;
                            }
                        }
                        Derived vd = acc.get(k.victim);
                        if (vd != null) {
                            vd.openingDeaths++;
                        }
                    }

                    if (killerSide != null && isTrade(round, k, sideOf, killerSide)) {
                        k.trade = true;
                        Derived kd = acc.get(k.killer);
                        if (kd != null) {
                            kd.tradeKills++;
                        }
                    }

                    killsThisRound.merge(k.killer, 1, Integer::sum);
                    Derived kd = acc.get(k.killer);
                    if (kd != null) {
                        if (k.weapon != null) {
                            kd.addWeapon(k.weapon);
                        }
                        if (killerSide != null) {
                            kd.side(killerSide)[0]++;
                        }
                    }
                }

                Side victimSide = sideOf.getOrDefault(k.victim, k.victimSide);
                if (victimSide != null && alive.get(victimSide).remove(k.victim)) {
                    died.add(k.victim);
                    Derived vd = acc.get(k.victim);
                    if (vd != null) {
                        vd.side(victimSide)[1]++;
                    }
                }

                if (!clutchesTrustworthy) {
                    continue;
                }
                for (Side side : Side.values()) {
                    Set<String> mine = alive.get(side);
                    Set<String> theirs = alive.get(side.other());
                    int start = side == Side.CT ? startCt : startT;
                    if (clutched.contains(side) || start <= 1 || mine.size() != 1
                            || theirs.isEmpty()) {
                        continue;
                    }
                    clutched.add(side);
                    String hero = mine.iterator().next();
                    Derived hd = acc.get(hero);
                    if (hd != null) {
                        hd.addClutch("1v" + theirs.size(), round.winner() == side);
                    }
                }
            }

            for (String name : participants) {
                Derived d = acc.get(name);
                if (d == null) {
                    continue;
                }
                d.roundsPlayed++;
                if (!died.contains(name)) {
                    d.roundsSurvived++;
                }
                Side side = sideOf.get(name);
                if (side != null) {
                    d.side(side)[2]++;
                }
            }

            for (Map.Entry<String, Integer> e : killsThisRound.entrySet()) {
                Derived d = acc.get(e.getKey());
                if (d != null && e.getValue() >= 2) {
                    d.addMultiKill(e.getValue());
                }
            }
        }

        return acc;
    }

    /** True when the player we just killed had killed one of our team inside the trade window. */
    private static boolean isTrade(RoundRecord round, KillEvent kill, Map<String, Side> sideOf,
                                   Side killerSide) {
        for (KillEvent prev : round.kills()) {
            if (prev.seq >= kill.seq || !kill.victim.equals(prev.killer)) {
                continue;
            }
            if (kill.ts - prev.ts > TRADE_WINDOW_MS) {
                continue;
            }
            if (sideOf.getOrDefault(prev.victim, prev.victimSide) == killerSide) {
                return true;
            }
        }
        return false;
    }
}
