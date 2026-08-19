package com.cestats;

import com.cestats.config.CeStatsConfig;
import com.cestats.ping.PingRelayClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug switch itself: a ping that cannot reach the relay must say so, and must say nothing at
 * all while the switch is off. The joined path needs a live site and is exercised manually.
 */
class PingDebugSinkTest {

    private record Line(String message, boolean ok) {
    }

    private static CeStatsConfig config(boolean debug) {
        CeStatsConfig config = new CeStatsConfig();
        config.enabled = true;
        config.pingEnabled = true;
        config.pingDebug = debug;
        config.deviceToken = "x".repeat(43);
        return config;
    }

    @Test
    @DisplayName("开启调试时，未加入频道的标点会说明原因")
    void reportsWhyAPingStayedLocal() {
        List<Line> lines = new ArrayList<>();
        PingRelayClient relay = new PingRelayClient(config(true),
                (message, ok) -> lines.add(new Line(message, ok)));
        relay.start();

        relay.publish("owner-abcd1234-7", "normal", 1.0, 2.0, 3.0, "minecraft:overworld");

        assertEquals(1, lines.size());
        assertFalse(lines.get(0).ok());
        // The trailing counter is what lets a player match a chat line to the click they just made.
        assertTrue(lines.get(0).message().startsWith("标点 #7 只在本地："),
                lines.get(0).message());
    }

    @Test
    @DisplayName("关闭调试时不产生任何输出")
    void staysSilentWhenDisabled() {
        List<Line> lines = new ArrayList<>();
        PingRelayClient relay = new PingRelayClient(config(false),
                (message, ok) -> lines.add(new Line(message, ok)));
        relay.start();

        relay.publish("owner-abcd1234-1", "normal", 1.0, 2.0, 3.0, "minecraft:overworld");

        assertEquals(List.of(), lines);
    }

    @Test
    @DisplayName("未配对时给出的原因是配对，而不是频道")
    void unpairedIsReportedAsPairing() {
        CeStatsConfig config = config(true);
        config.deviceToken = "";
        List<Line> lines = new ArrayList<>();
        PingRelayClient relay = new PingRelayClient(config,
                (message, ok) -> lines.add(new Line(message, ok)));
        relay.start();

        relay.publish("owner-abcd1234-2", "warning", 1.0, 2.0, 3.0, "minecraft:overworld");

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).message().contains("未配对"), lines.get(0).message());
    }
}
