package com.cestats;

import com.cestats.config.CeStatsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ping feature must stay opt-in. Enabling it takes over the vanilla "pick block" key, so a fresh
 * install — and an upgrade from a version whose config file predates the switch — has to come up with
 * it off. A default flipped back by accident is invisible in review and obvious in game.
 */
class PingDefaultsTest {

    @Test
    @DisplayName("标点功能默认关闭")
    void markersAreOffByDefault() {
        assertFalse(new CeStatsConfig().pingMarkerEnabled);
    }

    @Test
    @DisplayName("默认配置下中继不允许运行")
    void relayStaysDownUntilTheFeatureIsEnabled() {
        CeStatsConfig config = new CeStatsConfig();
        // The relay sub-switch is on by default, so the master switch is the only thing holding it.
        assertTrue(config.pingEnabled);
        assertFalse(config.pingRelayAllowed());
    }

    @Test
    @DisplayName("开启标点功能后中继才被允许，且仍受统计总开关约束")
    void enablingTheFeatureIsWhatLetsTheRelayRun() {
        CeStatsConfig config = new CeStatsConfig();
        config.pingMarkerEnabled = true;
        assertTrue(config.pingRelayAllowed());

        config.enabled = false;
        assertFalse(config.pingRelayAllowed());
    }
}
