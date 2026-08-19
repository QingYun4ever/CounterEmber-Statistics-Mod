package com.cestats;

import com.cestats.ping.PingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PingPolicyTest {

    @Test
    @DisplayName("普通标点受冷却限制")
    void cooldownBlocksNormalPing() {
        PingPolicy policy = new PingPolicy(3, 800);

        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(1_000, 0));
        assertEquals(PingPolicy.Decision.COOLDOWN, policy.tryAcceptNormal(1_799, 1));
        assertEquals(1, policy.cooldownRemainingMs(1_799));
        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(1_800, 1));
    }

    @Test
    @DisplayName("活动标点达到上限后拒绝新的普通标点")
    void capsActivePings() {
        PingPolicy policy = new PingPolicy(3, 0);

        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(1, 0));
        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(2, 1));
        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(3, 2));
        assertEquals(PingPolicy.Decision.LIMIT_REACHED, policy.tryAcceptNormal(4, 3));
    }

    @Test
    @DisplayName("警告升级刷新冷却，但不额外占用一个标点名额")
    void warningUpgradeRefreshesCooldown() {
        PingPolicy policy = new PingPolicy(3, 800);

        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(1_000, 0));
        policy.acceptWarning(1_200);
        assertEquals(800, policy.cooldownRemainingMs(1_200));
        assertEquals(PingPolicy.Decision.ACCEPTED, policy.tryAcceptNormal(2_000, 1));
    }

    @Test
    @DisplayName("上限和冷却参数不能为负数或零上限")
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new PingPolicy(0, 800));
        assertThrows(IllegalArgumentException.class, () -> new PingPolicy(3, -1));
    }
}
