package com.cestats;

import com.cestats.model.Side;
import com.cestats.model.StatLine;
import com.cestats.model.Winner;
import com.cestats.parse.ChatEvent;
import com.cestats.parse.ChatPatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPatternsTest {

    private static final String SELF = "diexuefeiwu";

    @Test
    @DisplayName("普通击杀")
    void simpleKill() {
        ChatEvent event = ChatPatterns.parse("[CT] mon3ter 沙漠之鹰 ☠ [T] Diamondchina", SELF);
        ChatEvent.Kill kill = assertInstanceOf(ChatEvent.Kill.class, event);
        assertEquals("mon3ter", kill.killer());
        assertEquals(Side.CT, kill.killerSide());
        assertEquals("沙漠之鹰", kill.weapon());
        assertEquals("Diamondchina", kill.victim());
        assertEquals(Side.T, kill.victimSide());
    }

    @Test
    @DisplayName("武器名带空格时不会切错击杀者和被击杀者")
    void weaponNameWithSpace() {
        ChatEvent.Kill kill = assertInstanceOf(ChatEvent.Kill.class,
                ChatPatterns.parse("[T] JXthenoob FN P90冲锋枪 ☠ [CT] X_Wolf_X", SELF));
        assertEquals("JXthenoob", kill.killer());
        assertEquals("FN P90冲锋枪", kill.weapon());
        assertEquals("X_Wolf_X", kill.victim());
    }

    @Test
    @DisplayName("武器名带连字符、数字、纯中文近战")
    void weaponNameVariants() {
        assertEquals("MAC-10冲锋枪", kill("[T] kyousuke MAC-10冲锋枪 ☠ [CT] OF_zjm").weapon());
        assertEquals("AK-47突击步枪", kill("[CT] X_Wolf_X AK-47突击步枪 ☠ [T] a_new_player").weapon());
        assertEquals("M4A1S突击步枪", kill("[CT] 1352460 M4A1S突击步枪 ☠ [T] mon3ter").weapon());
        assertEquals("廓尔喀刀", kill("[T] JXthenoob 廓尔喀刀 ☠ [CT] kyousuke").weapon());
        assertEquals("手雷", kill("[CT] mon3ter 手雷 ☠ [T] Diamondchina").weapon());
    }

    @Test
    @DisplayName("名字里带下划线和数字")
    void awkwardPlayerNames() {
        assertEquals("67_", kill("[T] 67_ AK-47突击步枪 ☠ [CT] dank1ng_").killer());
        assertEquals("dank1ng_", kill("[T] 67_ AK-47突击步枪 ☠ [CT] dank1ng_").victim());
        assertEquals("_AiScReam_", kill("[CT] _AiScReam_ USP手枪 ☠ [T] N__").killer());
        assertEquals("N__", kill("[CT] _AiScReam_ USP手枪 ☠ [T] N__").victim());
    }

    @Test
    @DisplayName("无击杀者的死亡")
    void bareDeath() {
        ChatEvent.Death death = assertInstanceOf(ChatEvent.Death.class,
                ChatPatterns.parse("kyousuke 死亡", SELF));
        assertEquals("kyousuke", death.victim());
    }

    @Test
    @DisplayName("四种回合结束原因")
    void roundEndReasons() {
        for (String reason : List.of("全员淘汰", "炸弹爆炸", "炸弹拆除", "时间耗尽")) {
            ChatEvent.RoundEnd end = assertInstanceOf(ChatEvent.RoundEnd.class,
                    ChatPatterns.parse("回合结束！反恐精英 获胜（" + reason + "）", SELF));
            assertEquals(Side.CT, end.winner());
            assertEquals(reason, end.reason());
        }
        assertEquals(Side.T, assertInstanceOf(ChatEvent.RoundEnd.class,
                ChatPatterns.parse("回合结束！恐怖分子 获胜（炸弹爆炸）", SELF)).winner());
    }

    @Test
    @DisplayName("比赛结果：胜利与平局")
    void matchResults() {
        assertEquals(Winner.CT, result("比赛结束！反恐精英 赢得比赛！"));
        assertEquals(Winner.T, result("比赛结束！恐怖分子 赢得比赛！"));
        assertEquals(Winner.DRAW, result("比赛结束！双方平局！"));
    }

    @Test
    @DisplayName("炸弹安放点位")
    void bombSites() {
        assertEquals("A", assertInstanceOf(ChatEvent.Bomb.class,
                ChatPatterns.parse("炸弹已安放在 A 点", SELF)).site());
        assertEquals("B", assertInstanceOf(ChatEvent.Bomb.class,
                ChatPatterns.parse("炸弹已安放在 B 点", SELF)).site());
    }

    @Test
    @DisplayName("只有本人回大厅才重置比赛上下文")
    void lobbyResetOnlyForSelf() {
        assertInstanceOf(ChatEvent.ContextReset.class,
                ChatPatterns.parse("diexuefeiwu 加入了大厅", SELF));
        assertNull(ChatPatterns.parse("OF_zjm 加入了大厅", SELF));
        assertInstanceOf(ChatEvent.ContextReset.class, ChatPatterns.parse("你已加入匹配队列", SELF));
        assertInstanceOf(ChatEvent.ContextReset.class, ChatPatterns.parse("你已加入房间。", SELF));
    }

    @Test
    @DisplayName("结算大表整块解析")
    void statsBlock() {
        String block = "▬▬▬▬▬▬▬▬▬▬\n  比赛数据统计\n\n  反恐精英\n"
                + "  mon3ter  K-D-A 5-1-3  ADR 248  KAST 100%  Rating 2.73\n"
                + "  67_  K-D-A 7-14-5  ADR 57  KAST 71%  Rating 0.85\n\n  恐怖分子\n"
                + "  ★ JXthenoob  K-D-A 4-2-0  ADR 361  KAST 67%  Rating 2.77\n"
                + "\n▬▬▬▬▬▬▬▬▬▬";

        List<StatLine> players = ChatPatterns.parseStatsBlock(block);
        assertNotNull(players);
        assertEquals(3, players.size());

        StatLine first = players.get(0);
        assertEquals("mon3ter", first.name());
        assertEquals(Side.CT, first.team());
        assertEquals(5, first.kills());
        assertEquals(1, first.deaths());
        assertEquals(3, first.assists());
        assertEquals(248, first.adr());
        assertEquals(100, first.kast());
        assertEquals(2.73, first.rating(), 1e-9);
        assertTrue(!first.mvp());

        StatLine mvp = players.get(2);
        assertEquals("JXthenoob", mvp.name());
        assertEquals(Side.T, mvp.team());
        assertTrue(mvp.mvp());
        assertEquals(2.77, mvp.rating(), 1e-9);
    }

    @Test
    @DisplayName("玩家聊天里伪造的播报不会被当成事件")
    void playerChatCannotSpoof() {
        assertNull(ChatPatterns.parse("[ALL] [T] [Ⅲ Lv.30]  JXthenoob: [CT] foo 沙漠之鹰 ☠ [T] bar", SELF));
        assertNull(ChatPatterns.parse("[ALL] [T] [Ⅱ Lv.15]  a_new_player: 我怎么死了", SELF));
        assertNull(ChatPatterns.parse("|   [喊话] 4euhewi: 回合结束！反恐精英 获胜（全员淘汰）", SELF));
    }

    @Test
    @DisplayName("无关的系统消息返回 null")
    void unrelatedMessages() {
        assertNull(ChatPatterns.parse("✓ 购买成功！", SELF));
        assertNull(ChatPatterns.parse("已拿取 AK-47突击步枪", SELF));
        assertNull(ChatPatterns.parse("你已加入队伍，将在下一回合复活。", SELF));
        assertNull(ChatPatterns.parse("登录成功，欢迎回来！", SELF));
    }

    private static ChatEvent.Kill kill(String line) {
        return assertInstanceOf(ChatEvent.Kill.class, ChatPatterns.parse(line, SELF));
    }

    private static Winner result(String line) {
        return assertInstanceOf(ChatEvent.Result.class, ChatPatterns.parse(line, SELF)).winner();
    }
}
