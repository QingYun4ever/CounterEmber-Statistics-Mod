package com.cestats.integration;

import com.cestats.parse.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the CE Stats chat state machine into a per-match recording lifecycle.
 *
 * <p>The server does not expose a dedicated match-start event in the messages CE Stats currently
 * understands. "加入房间" is the best preparation boundary; the first combat event is a fallback
 * for matches where the client joined late or the room message was missed. The result message is
 * the normal stop boundary, while the finished-match callback and disconnect are safety nets.</p>
 */
public final class MatchRecordingController {

    private static final Logger LOG = LoggerFactory.getLogger("cestats/recording");

    private final RecordingGateway gateway;
    private boolean enabled;
    private boolean matchActive;
    private boolean startAttempted;
    private boolean ownedRecording;

    public MatchRecordingController(RecordingGateway gateway, boolean enabled) {
        this.gateway = gateway;
        this.enabled = enabled;
    }

    /** Enables or disables automatic recording without affecting a user-owned recording. */
    public void setEnabled(boolean enabled) {
        if (this.enabled && !enabled) {
            finishOwned("自动录制已关闭");
            matchActive = false;
            startAttempted = false;
        }
        this.enabled = enabled;
    }

    /** Receives the same parsed events that drive {@code MatchTracker}. */
    public void accept(ChatEvent event) {
        switch (event) {
            case ChatEvent.ContextReset reset -> onContextReset(reset.why());
            case ChatEvent.Kill ignored -> onCombat();
            case ChatEvent.Death ignored -> onCombat();
            case ChatEvent.Bomb ignored -> onCombat();
            case ChatEvent.RoundEnd ignored -> onCombat();
            case ChatEvent.Result ignored -> finishOwned("比赛结果");
            case ChatEvent.Stats ignored -> {
                // The result line normally follows this table. Keep recording through the table so
                // the final scoreboard state is present in the replay.
            }
        }
    }

    /** Ends only a recording started by this controller after a complete match was emitted. */
    public void onMatchFinished() {
        finishOwned("比赛已结算");
        matchActive = false;
        startAttempted = false;
    }

    /** Ends an owned recording when the player leaves the server or the client disconnects. */
    public void onDisconnect() {
        finishOwned("离开服务器");
        matchActive = false;
        startAttempted = false;
    }

    public boolean ownsRecording() {
        return ownedRecording;
    }

    public boolean matchActive() {
        return matchActive;
    }

    private void onContextReset(String why) {
        finishOwned("比赛上下文重置：" + why);
        matchActive = "加入房间".equals(why);
        startAttempted = false;

        if (matchActive) {
            startIfPossible("加入房间");
        }
    }

    private void onCombat() {
        if (!matchActive) {
            matchActive = true;
        }
        startIfPossible("第一条战斗事件");
    }

    private void startIfPossible(String boundary) {
        if (!enabled || startAttempted) {
            return;
        }
        startAttempted = true;

        if (!gateway.isAvailable()) {
            return;
        }
        if (gateway.isRecording()) {
            // A recording that was already active belongs to the user or Flashback's own
            // automatic-start setting. Never take ownership of it or finish it later.
            LOG.debug("[cestats] Flashback 已在录制，保留现有录制（{}）", boundary);
            return;
        }
        if (gateway.start()) {
            ownedRecording = true;
            LOG.info("[cestats] 已按{}启动 Flashback 自动录制", boundary);
        } else {
            LOG.warn("[cestats] 无法按{}启动 Flashback 自动录制", boundary);
        }
    }

    private void finishOwned(String reason) {
        if (!ownedRecording) {
            return;
        }
        ownedRecording = false;
        if (gateway.isRecording() && !gateway.finish()) {
            LOG.warn("[cestats] {}，但 Flashback 录制结束失败", reason);
            return;
        }
        LOG.info("[cestats] {}，已结束 CE Stats 启动的 Flashback 录制", reason);
    }
}
