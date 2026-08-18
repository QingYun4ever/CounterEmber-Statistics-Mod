package com.cestats;

import com.cestats.match.MatchTracker;
import com.cestats.model.MatchRecord;
import com.cestats.model.PlayerRecord;
import com.cestats.model.Side;
import com.cestats.model.StatLine;
import com.cestats.model.Winner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * Replays the real chat log captured from IMC.RE and checks the whole pipeline against the
 * numbers the server itself printed.
 *
 * <p>The expected match ids are the ones the TypeScript implementation
 * ({@code web/src/lib/parse.ts}) produced from the same log. They only match if both sides agree
 * on the roster, every stat value and the id hashing — which is the cross-validation this project
 * relies on.
 */
class MatchTrackerTest {

    private static final Pattern LOG_LINE =
            Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\] \\[[^\\]]+\\]: \\[System\\] \\[CHAT\\] (.*)$");

    /** The log has no date, only wall-clock times; anchor it so the test is deterministic. */
    private static final long DAY_START = LocalDate.of(2026, 8, 18)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

    private static List<MatchRecord> matches;

    @BeforeAll
    static void replayLog() throws IOException {
        List<MatchRecord> found = new ArrayList<>();
        MatchTracker tracker = new MatchTracker(found::add);
        tracker.setContext("off.s4.imc.cab", "diexuefeiwu");

        try (InputStream in = MatchTrackerTest.class.getResourceAsStream("/latest-chat.log")) {
            assertNotNull(in, "缺少测试资源 latest-chat.log");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int day = 0;
            int prevSecs = -1;
            while ((line = reader.readLine()) != null) {
                Matcher m = LOG_LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                int secs = Integer.parseInt(m.group(1)) * 3600
                        + Integer.parseInt(m.group(2)) * 60
                        + Integer.parseInt(m.group(3));
                if (prevSecs >= 0 && secs < prevSecs) {
                    day++;
                }
                prevSecs = secs;
                String content = m.group(4).replace("\\r", "\r").replace("\\n", "\n");
                tracker.accept(content, DAY_START + day * 86_400_000L + secs * 1000L);
            }
        }
        matches = found;
    }

    @Test
    @DisplayName("日志里的 4 场比赛全部被识别")
    void findsEveryMatch() {
        assertEquals(4, matches.size());
    }

    @Test
    @DisplayName("matchId 与 TypeScript 实现逐字节一致")
    void matchIdsAgreeWithTypescript() {
        assertEquals(
                List.of("1eec2cc4d576290d", "99dac65b63ca100f", "f9c6d228d8888e5b", "89668c715f991bec"),
                matches.stream().map(MatchRecord::matchId).toList());
    }

    @Test
    @DisplayName("胜负、比分、回合数、观测完整性")
    void matchLevelFields() {
        MatchRecord first = matches.get(0);
        assertEquals(Winner.CT, first.winner());
        assertEquals(4, first.roundsObserved());
        assertEquals(3, first.ctScore());
        assertEquals(1, first.tScore());
        assertTrue(!first.complete(), "客户端是中途加入的，不应标记为完整");

        MatchRecord third = matches.get(2);
        assertEquals(Winner.T, third.winner());
        assertEquals(15, third.roundsObserved());
        assertTrue(third.complete(), "整场都观测到了，应标记为完整");

        MatchRecord fourth = matches.get(3);
        assertEquals(Winner.DRAW, fourth.winner());
        assertEquals(16, fourth.roundsObserved());
    }

    @Test
    @DisplayName("队伍比分与服务器宣布的胜方一致（阵营会在半场交换）")
    void teamScoreAgreesWithAnnouncedWinner() {
        for (MatchRecord match : matches) {
            if (match.winner() == Winner.CT) {
                assertTrue(match.ctScore() > match.tScore(),
                        match.matchId() + ": 宣布 CT 获胜，但比分是 " + match.ctScore() + ":" + match.tScore());
            } else if (match.winner() == Winner.T) {
                assertTrue(match.tScore() > match.ctScore(),
                        match.matchId() + ": 宣布 T 获胜，但比分是 " + match.ctScore() + ":" + match.tScore());
            }
        }
    }

    @Test
    @DisplayName("结算表每个字段都与服务器播报逐字一致")
    void statTableParsedExactly() {
        MatchRecord match = matches.get(2);
        assertEquals(7, match.players().size());

        assertStat(match, "salty1145", Side.T, 20, 7, 4, 183, 87, 2.35, true);
        assertStat(match, "diexuefeiwu", Side.T, 9, 6, 2, 146, 80, 1.49, false);
        assertStat(match, "X_Wolf_X", Side.T, 8, 6, 6, 100, 80, 1.32, false);
        assertStat(match, "a357b", Side.T, 8, 8, 4, 102, 87, 1.25, false);
        assertStat(match, "Diamondchina", Side.CT, 6, 14, 6, 76, 53, 0.69, false);
        assertStat(match, "1352460", Side.CT, 2, 8, 3, 59, 30, 0.41, false);
        assertStat(match, "OF_zjm", Side.CT, 4, 15, 5, 40, 40, 0.33, false);

        assertEquals("salty1145", match.mvp());
    }

    @Test
    @DisplayName("★ 永远标记全场 Rating 最高的玩家")
    void mvpIsTopRating() {
        for (MatchRecord match : matches) {
            double best = match.players().stream()
                    .mapToDouble(p -> p.stat().rating()).max().orElseThrow();
            PlayerRecord mvp = match.player(match.mvp());
            assertNotNull(mvp, match.matchId() + " 没有解析出 MVP");
            assertEquals(best, mvp.stat().rating(), 1e-9, match.matchId());
        }
    }

    @Test
    @DisplayName("完整观测的比赛：击杀播报数 >= 结算表 K 之和")
    void killFeedReconciles() {
        MatchRecord match = matches.get(2);
        int observed = match.rounds().stream()
                .mapToInt(r -> (int) r.kills().stream().filter(k -> k.killer != null).count())
                .sum();
        int table = match.players().stream().mapToInt(p -> p.stat().kills()).sum();
        assertEquals(57, table);
        assertTrue(observed >= table, "观测 " + observed + " < 结算表 " + table);
    }

    @Test
    @DisplayName("推断指标落在合理范围内")
    void derivedStatsAreSane() {
        for (MatchRecord match : matches) {
            for (PlayerRecord p : match.players()) {
                var d = p.derived();
                assertTrue(d.roundsSurvived <= d.roundsPlayed, p.stat().name());
                assertTrue(d.roundsPlayed <= match.roundsObserved(), p.stat().name());
                assertTrue(d.openingKills + d.openingDeaths <= match.roundsObserved(), p.stat().name());
                for (var entry : d.clutches.entrySet()) {
                    int n = Integer.parseInt(entry.getKey().substring(2));
                    assertTrue(n >= 1 && n <= 5,
                            "不可能的残局 " + entry.getKey() + " (" + p.stat().name() + ")");
                    assertTrue(entry.getValue()[0] <= entry.getValue()[1], entry.getKey());
                }
            }
        }
    }

    @Test
    @DisplayName("每回合恰好一次首杀（有击杀的回合）")
    void oneOpeningPerRound() {
        for (MatchRecord match : matches) {
            for (var round : match.rounds()) {
                long openings = round.kills().stream().filter(k -> k.opening).count();
                boolean anyKill = round.kills().stream().anyMatch(k -> k.killer != null);
                assertEquals(anyKill ? 1 : 0, openings,
                        match.matchId() + " R" + (round.idx() + 1));
            }
        }
    }

    @Test
    @DisplayName("四种回合结束原因和炸弹点位都能命中")
    void roundReasonsAndBombSites() {
        var reasons = matches.stream()
                .flatMap(m -> m.rounds().stream())
                .map(r -> r.reason())
                .distinct()
                .sorted()
                .toList();
        assertTrue(reasons.contains("全员淘汰"));
        assertTrue(reasons.contains("炸弹拆除"));
        assertTrue(reasons.contains("时间耗尽"));

        boolean anyBomb = matches.stream()
                .flatMap(m -> m.rounds().stream())
                .anyMatch(r -> "A".equals(r.bombSite()) || "B".equals(r.bombSite()));
        assertTrue(anyBomb, "没有解析到任何炸弹安放点位");
    }

    private static void assertStat(MatchRecord match, String name, Side team, int k, int d, int a,
                                   int adr, int kast, double rating, boolean mvp) {
        PlayerRecord record = match.player(name);
        assertNotNull(record, name + " 未出现在结算表里");
        StatLine s = record.stat();
        assertEquals(team, s.team(), name + " 阵营");
        assertEquals(k, s.kills(), name + " 击杀");
        assertEquals(d, s.deaths(), name + " 死亡");
        assertEquals(a, s.assists(), name + " 助攻");
        assertEquals(adr, s.adr(), name + " ADR");
        assertEquals(kast, s.kast(), name + " KAST");
        assertEquals(rating, s.rating(), 1e-9, name + " Rating");
        assertEquals(mvp, s.mvp(), name + " MVP");
    }
}
