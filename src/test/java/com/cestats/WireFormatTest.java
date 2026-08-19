package com.cestats;

import com.cestats.match.MatchTracker;
import com.cestats.model.MatchRecord;
import com.cestats.net.MatchJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the uploaded payload against the shape {@code /api/ingest} validates, and writes each
 * match to {@code build/wire/} so the real endpoint can be exercised with them:
 *
 * <pre>curl -X POST localhost:3100/api/ingest -H "Authorization: Bearer &lt;device-token&gt;" -d @build/wire/&lt;id&gt;.json</pre>
 */
class WireFormatTest {

    private static final Pattern LOG_LINE =
            Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\] \\[[^\\]]+\\]: \\[System\\] \\[CHAT\\] (.*)$");
    private static final long DAY_START = LocalDate.of(2026, 8, 18)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

    @Test
    @DisplayName("上传载荷字段齐全，并导出到 build/wire 供接口联调")
    void serialisesToIngestShape() throws IOException {
        List<MatchRecord> matches = replay();
        assertEquals(4, matches.size());

        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Path out = Path.of("build", "wire");
        Files.createDirectories(out);

        for (MatchRecord match : matches) {
            JsonObject json = MatchJson.toJson(match);

            for (String key : List.of("version", "server", "uploader", "endedAt", "winner", "mvp",
                    "ctScore", "tScore", "roundsObserved", "complete", "players", "rounds")) {
                assertTrue(json.has(key), "缺少字段 " + key);
            }
            assertEquals(1, json.get("version").getAsInt());
            assertEquals(match.players().size(), json.getAsJsonArray("players").size());
            assertEquals(match.rounds().size(), json.getAsJsonArray("rounds").size());

            JsonObject player = json.getAsJsonArray("players").get(0).getAsJsonObject();
            for (String key : List.of("name", "team", "kills", "deaths", "assists", "adr", "kast",
                    "rating", "isMvp", "derived")) {
                assertTrue(player.has(key), "players[] 缺少字段 " + key);
            }
            JsonObject derived = player.getAsJsonObject("derived");
            for (String key : List.of("openingKills", "openingDeaths", "openingRoundWins",
                    "tradeKills", "mk2", "mk3", "mk4", "mk5", "clutches", "roundsSurvived",
                    "roundsPlayed", "weapons", "sides")) {
                assertTrue(derived.has(key), "derived 缺少字段 " + key);
            }

            if (!match.rounds().isEmpty()) {
                JsonObject round = json.getAsJsonArray("rounds").get(0).getAsJsonObject();
                for (String key : List.of("idx", "winner", "reason", "bombSite", "kills")) {
                    assertTrue(round.has(key), "rounds[] 缺少字段 " + key);
                }
            }

            // A round can legitimately end with nobody dying (bomb defused, time out), so pick
            // the first round that actually has a kill.
            JsonObject kill = firstKill(json);
            if (kill != null) {
                for (String key : List.of("seq", "ts", "killer", "killerSide", "weapon", "victim",
                        "victimSide", "isOpening", "isTrade")) {
                    assertTrue(kill.has(key), "kills[] 缺少字段 " + key);
                }
            }

            // The id is never sent: the server recomputes it so a buggy client cannot duplicate.
            assertTrue(!json.has("matchId"));

            Files.writeString(out.resolve(match.matchId() + ".json"), gson.toJson(json),
                    StandardCharsets.UTF_8);
        }
    }

    private static JsonObject firstKill(JsonObject match) {
        for (var round : match.getAsJsonArray("rounds")) {
            var kills = round.getAsJsonObject().getAsJsonArray("kills");
            if (!kills.isEmpty()) {
                return kills.get(0).getAsJsonObject();
            }
        }
        return null;
    }

    private static List<MatchRecord> replay() throws IOException {
        List<MatchRecord> found = new ArrayList<>();
        MatchTracker tracker = new MatchTracker(found::add);
        tracker.setContext("off.s4.imc.cab", "diexuefeiwu");

        try (InputStream in = WireFormatTest.class.getResourceAsStream("/latest-chat.log")) {
            assertNotNull(in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = LOG_LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                long secs = Integer.parseInt(m.group(1)) * 3600L
                        + Integer.parseInt(m.group(2)) * 60L
                        + Integer.parseInt(m.group(3));
                tracker.accept(m.group(4).replace("\\r", "\r").replace("\\n", "\n"),
                        DAY_START + secs * 1000L);
            }
        }
        return found;
    }
}
