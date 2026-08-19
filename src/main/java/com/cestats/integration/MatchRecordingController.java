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
    private static final int MAX_START_ATTEMPTS = 100;

    private final RecordingGateway gateway;
    private boolean enabled;
    private boolean matchActive;
    private boolean startPending;
    private int startAttemptsRemaining;
    private boolean ownedRecording;

    public MatchRecordingController(RecordingGateway gateway, boolean enabled) {
        this.gateway = gateway;
        this.enabled = enabled;
    }

    /** Enables or disables automatic recording without affecting a user-owned recording. */
    public void setEnabled(boolean enabled) {
        if (!enabled && (this.enabled || ownedRecording)) {
            finishOwned("自动录制已关闭");
            matchActive = false;
            clearStartRequest();
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
        clearStartRequest();
    }

    /** Ends an owned recording when the player leaves the server or the client disconnects. */
    public void onDisconnect() {
        finishOwned("离开服务器");
        matchActive = false;
        clearStartRequest();
    }

    /**
     * Retries a start that arrived while the client was still loading the player/world. Flashback
     * requires a live player registry, so doing the retry from the client tick avoids a race with
     * the first room chat packet.
     */
    public void tick() {
        if (ownedRecording && !matchActive) {
            // A failed finish must not leave a replay open forever after disconnect or a config
            // toggle. Keep retrying on the client thread; finishOwned is idempotent while the
            // recorder is already closed.
            finishOwned("客户端 tick 重试");
        }
        if (startPending && enabled && matchActive && !ownedRecording) {
            startIfPossible("客户端 tick");
        }
    }

    public boolean isAvailable() {
        return gateway.isAvailable();
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
        clearStartRequest();

        if (matchActive) {
            requestStart("加入房间");
        }
    }

    private void onCombat() {
        if (!matchActive) {
            matchActive = true;
        }
        requestStart("第一条战斗事件");
    }

    private void requestStart(String boundary) {
        if (!enabled || ownedRecording) {
            return;
        }
        startPending = true;
        startAttemptsRemaining = MAX_START_ATTEMPTS;
        startIfPossible(boundary);
    }

    private void startIfPossible(String boundary) {
        if (!enabled || !matchActive || !startPending || ownedRecording
                || startAttemptsRemaining <= 0) {
            return;
        }
        startAttemptsRemaining--;

        if (!gateway.isAvailable()) {
            // There is no point retrying when Flashback is absent or its public entry points do
            // not match this client version.
            clearStartRequest();
            return;
        }
        if (gateway.isRecording()) {
            // A recording that was already active belongs to the user or Flashback's own
            // automatic-start setting. Never take ownership of it or finish it later.
            clearStartRequest();
            LOG.debug("[cestats] Flashback 已在录制，保留现有录制（{}）", boundary);
            return;
        }
        if (gateway.start()) {
            ownedRecording = true;
            clearStartRequest();
            LOG.info("[cestats] 已按{}启动 Flashback 自动录制", boundary);
        } else if (startAttemptsRemaining == MAX_START_ATTEMPTS - 1) {
            LOG.warn("[cestats] 无法按{}启动 Flashback 自动录制，将在客户端就绪后重试", boundary);
        }
    }

    private void clearStartRequest() {
        startPending = false;
        startAttemptsRemaining = 0;
    }

    private void finishOwned(String reason) {
        if (!ownedRecording) {
            return;
        }
        if (gateway.isRecording()) {
            if (!gateway.finish()) {
                LOG.warn("[cestats] {}，但 Flashback 录制结束失败，将在下次安全边界重试", reason);
                return;
            }
        }
        ownedRecording = false;
        LOG.info("[cestats] {}，已结束 CE Stats 启动的 Flashback 录制", reason);
    }
}
