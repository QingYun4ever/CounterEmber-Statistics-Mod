package com.cestats.net;

import com.cestats.model.Derived;
import com.cestats.model.KillEvent;
import com.cestats.model.MatchRecord;
import com.cestats.model.PlayerRecord;
import com.cestats.model.RoundRecord;
import com.cestats.model.Side;
import com.cestats.model.StatLine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Serialises a {@link MatchRecord} into the exact wire shape {@code /api/ingest} validates
 * ({@code zMatch} in {@code web/src/lib/protocol.ts}). Field names and nullability must match.
 *
 * <p>{@code matchId} is intentionally not sent: the server recomputes it so a buggy client
 * cannot create duplicates.
 */
public final class MatchJson {

    private MatchJson() {
    }

    public static JsonObject toJson(MatchRecord match) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", match.server());
        root.addProperty("uploader", match.uploader());
        root.addProperty("endedAt", match.endedAt());
        root.addProperty("winner", match.winner().name());
        if (match.mvp() != null) {
            root.addProperty("mvp", match.mvp());
        } else {
            root.add("mvp", com.google.gson.JsonNull.INSTANCE);
        }
        root.addProperty("ctScore", match.ctScore());
        root.addProperty("tScore", match.tScore());
        root.addProperty("roundsObserved", match.roundsObserved());
        root.addProperty("complete", match.complete());

        JsonArray players = new JsonArray();
        for (PlayerRecord p : match.players()) {
            players.add(player(p));
        }
        root.add("players", players);

        JsonArray rounds = new JsonArray();
        for (RoundRecord r : match.rounds()) {
            rounds.add(round(r));
        }
        root.add("rounds", rounds);

        return root;
    }

    private static JsonObject player(PlayerRecord record) {
        StatLine stat = record.stat();
        JsonObject json = new JsonObject();
        json.addProperty("name", stat.name());
        json.addProperty("team", stat.team().name());
        json.addProperty("kills", stat.kills());
        json.addProperty("deaths", stat.deaths());
        json.addProperty("assists", stat.assists());
        json.addProperty("adr", stat.adr());
        json.addProperty("kast", stat.kast());
        json.addProperty("rating", stat.rating());
        json.addProperty("isMvp", stat.mvp());
        json.add("derived", derived(record.derived()));
        return json;
    }

    private static JsonObject derived(Derived d) {
        JsonObject json = new JsonObject();
        json.addProperty("openingKills", d.openingKills);
        json.addProperty("openingDeaths", d.openingDeaths);
        json.addProperty("openingRoundWins", d.openingRoundWins);
        json.addProperty("tradeKills", d.tradeKills);
        json.addProperty("mk2", d.mk2);
        json.addProperty("mk3", d.mk3);
        json.addProperty("mk4", d.mk4);
        json.addProperty("mk5", d.mk5);

        JsonObject clutches = new JsonObject();
        for (Map.Entry<String, int[]> e : d.clutches.entrySet()) {
            JsonArray pair = new JsonArray();
            pair.add(e.getValue()[0]);
            pair.add(e.getValue()[1]);
            clutches.add(e.getKey(), pair);
        }
        json.add("clutches", clutches);

        json.addProperty("roundsSurvived", d.roundsSurvived);
        json.addProperty("roundsPlayed", d.roundsPlayed);

        JsonObject weapons = new JsonObject();
        for (Map.Entry<String, Integer> e : d.weapons.entrySet()) {
            weapons.addProperty(e.getKey(), e.getValue());
        }
        json.add("weapons", weapons);

        JsonObject sides = new JsonObject();
        for (Map.Entry<Side, int[]> e : d.sides.entrySet()) {
            JsonObject split = new JsonObject();
            split.addProperty("kills", e.getValue()[0]);
            split.addProperty("deaths", e.getValue()[1]);
            split.addProperty("rounds", e.getValue()[2]);
            sides.add(e.getKey().name(), split);
        }
        json.add("sides", sides);

        return json;
    }

    private static JsonObject round(RoundRecord round) {
        JsonObject json = new JsonObject();
        json.addProperty("idx", round.idx());
        json.addProperty("winner", round.winner().name());
        json.addProperty("reason", round.reason());
        if (round.bombSite() != null) {
            json.addProperty("bombSite", round.bombSite());
        } else {
            json.add("bombSite", com.google.gson.JsonNull.INSTANCE);
        }

        JsonArray kills = new JsonArray();
        for (KillEvent k : round.kills()) {
            JsonObject kill = new JsonObject();
            kill.addProperty("seq", k.seq);
            kill.addProperty("ts", k.ts);
            addNullable(kill, "killer", k.killer);
            addNullable(kill, "killerSide", k.killerSide == null ? null : k.killerSide.name());
            addNullable(kill, "weapon", k.weapon);
            kill.addProperty("victim", k.victim);
            addNullable(kill, "victimSide", k.victimSide == null ? null : k.victimSide.name());
            kill.addProperty("isOpening", k.opening);
            kill.addProperty("isTrade", k.trade);
            kills.add(kill);
        }
        json.add("kills", kills);
        return json;
    }

    private static void addNullable(JsonObject json, String key, String value) {
        if (value == null) {
            json.add(key, com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty(key, value);
        }
    }
}
