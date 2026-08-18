package com.cestats.parse;

import com.cestats.model.Side;
import com.cestats.model.StatLine;
import com.cestats.model.Winner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat parsing for the IMC.RE "团队爆破" (CS2-alike) game mode.
 *
 * <p>Verified line by line against a real latest.log. Two shapes drive the design:
 * <ul>
 *   <li>player names never contain spaces but WEAPON names do ("FN 冲锋枪"), so a kill line is
 *       read as first token = killer, last token = victim, everything between = weapon;</li>
 *   <li>the end-of-match table arrives as ONE chat message with embedded newlines.</li>
 * </ul>
 *
 * <p>This class deliberately has no Minecraft dependency so it can be unit tested against raw
 * log lines. It is the Java twin of {@code web/src/lib/parse.ts}; the two must stay in sync.
 */
public final class ChatPatterns {

    public static final Pattern KILL =
            Pattern.compile("^\\[(CT|T)\\] (\\S+) (.+?) ☠ \\[(CT|T)\\] (\\S+)$");
    public static final Pattern DEATH = Pattern.compile("^(\\S+) 死亡$");
    public static final Pattern ROUND_END =
            Pattern.compile("^回合结束！(反恐精英|恐怖分子) 获胜（(.+)）$");
    public static final Pattern BOMB = Pattern.compile("^炸弹已安放在 ([AB]) 点$");
    public static final Pattern RESULT =
            Pattern.compile("^比赛结束！(?:(反恐精英|恐怖分子) 赢得比赛！|双方平局！)$");
    public static final Pattern STAT_LINE = Pattern.compile(
            "^(★\\s+)?(\\S+)\\s+K-D-A\\s+(\\d+)-(\\d+)-(\\d+)\\s+ADR\\s+(\\d+)"
                    + "\\s+KAST\\s+(\\d+)%\\s+Rating\\s+([\\d.]+)$");

    public static final Pattern LOBBY = Pattern.compile("^(\\S+) 加入了大厅$");
    public static final Pattern QUEUE = Pattern.compile("^你已加入匹配队列$");
    public static final Pattern ROOM = Pattern.compile("^你已加入房间。$");

    private static final String STATS_MARKER = "比赛数据统计";

    private ChatPatterns() {
    }

    /** Parses the end-of-match table, or returns null if this message is not that table. */
    public static List<StatLine> parseStatsBlock(String content) {
        if (!content.contains(STATS_MARKER)) {
            return null;
        }
        List<StatLine> players = new ArrayList<>();
        Side team = null;
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals(Side.TEAM_CT)) {
                team = Side.CT;
                continue;
            }
            if (line.equals(Side.TEAM_T)) {
                team = Side.T;
                continue;
            }
            if (team == null) {
                continue;
            }
            Matcher m = STAT_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            players.add(new StatLine(
                    m.group(2),
                    team,
                    m.group(1) != null,
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7)),
                    Double.parseDouble(m.group(8))));
        }
        return players.isEmpty() ? null : players;
    }

    /**
     * Maps one raw system chat message to an event, or null if we do not care about it.
     *
     * @param selfName the local player, needed to tell "I went back to the lobby" apart from the
     *                 same broadcast about somebody else
     */
    public static ChatEvent parse(String content, String selfName) {
        List<StatLine> stats = parseStatsBlock(content);
        if (stats != null) {
            return new ChatEvent.Stats(stats);
        }

        String line = content.trim();

        Matcher m = KILL.matcher(line);
        if (m.matches()) {
            return new ChatEvent.Kill(
                    m.group(2), Side.valueOf(m.group(1)), m.group(3).trim(),
                    m.group(5), Side.valueOf(m.group(4)));
        }

        m = ROUND_END.matcher(line);
        if (m.matches()) {
            return new ChatEvent.RoundEnd(Side.fromTeam(m.group(1)), m.group(2));
        }

        m = RESULT.matcher(line);
        if (m.matches()) {
            return new ChatEvent.Result(
                    m.group(1) != null ? Winner.ofSide(Side.fromTeam(m.group(1))) : Winner.DRAW);
        }

        m = BOMB.matcher(line);
        if (m.matches()) {
            return new ChatEvent.Bomb(m.group(1));
        }

        if (QUEUE.matcher(line).matches()) {
            return new ChatEvent.ContextReset("加入匹配队列");
        }
        if (ROOM.matcher(line).matches()) {
            return new ChatEvent.ContextReset("加入房间");
        }

        m = LOBBY.matcher(line);
        if (m.matches()) {
            return m.group(1).equals(selfName) ? new ChatEvent.ContextReset("加入大厅") : null;
        }

        m = DEATH.matcher(line);
        if (m.matches()) {
            return new ChatEvent.Death(m.group(1));
        }

        return null;
    }
}
