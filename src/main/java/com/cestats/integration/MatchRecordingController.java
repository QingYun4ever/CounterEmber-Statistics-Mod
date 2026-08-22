package com.cestats.integration;

import com.cestats.model.Side;
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

    /** Marker colours, {@code 0xRRGGBB}: the killer's side decides, so a feed reads at a glance. */
    private static final int COLOUR_CT = 0x4FA3FF;
    private static final int COLOUR_T = 0xFFC24F;
    /** Deaths with no killer, and round/bomb boundaries, which belong to neither side. */
    private static final int COLOUR_NEUTRAL = 0xAAAAAA;

    private final RecordingGateway gateway;
    private boolean enabled;
    private boolean markKills;
    private boolean matchActive;
    private boolean startPending;
    private int startAttemptsRemaining;
    private boolean ownedRecording;

    public MatchRecordingController(RecordingGateway gateway, boolean enabled) {
        this(gateway, enabled, false);
    }

    public MatchRecordingController(RecordingGateway gateway, boolean enabled, boolean markKills) {
        this.gateway = gateway;
        this.enabled = enabled;
        this.markKills = markKills;
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

    /**
     * Toggles kill markers on the replay timeline. Independent of {@link #setEnabled}: marking only
     * adds bookmarks to whatever is already being recorded, so it is safe while a recording the user
     * started themselves is running.
     */
    public void setMarkKills(boolean markKills) {
        this.markKills = markKills;
    }

    /** Receives the same parsed events that drive {@code MatchTracker}. */
    public void accept(ChatEvent event) {
        switch (event) {
            case ChatEvent.ContextReset reset -> onContextReset(reset.why());
            case ChatEvent.Kill kill -> {
                onCombat();
                mark(describeKill(kill), colourOf(kill.killerSide()));
            }
            case ChatEvent.Death death -> {
                onCombat();
                mark(death.victim() + " 死亡", COLOUR_NEUTRAL);
            }
            case ChatEvent.Bomb bomb -> {
                onCombat();
                mark("安放炸弹 " + bomb.site(), COLOUR_NEUTRAL);
            }
            case ChatEvent.RoundEnd end -> {
                onCombat();
                mark("回合结束：" + end.winner().teamName() + " " + end.reason(), COLOUR_NEUTRAL);
            }
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

    /** Whether Flashback exposed a marker entry point on this client. */
    public boolean supportsMarks() {
        return gateway.supportsMarks();
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

    /**
     * Marks the timeline whenever something is recording, including a recording the user started.
     * Ownership only governs start/finish; a marker adds no state the user has to clean up, and
     * refusing to mark a manually started recording would be the surprising behaviour.
     */
    private void mark(String description, int colour) {
        if (!markKills || !gateway.isRecording()) {
            return;
        }
        gateway.mark(description, colour);
    }

    private static String describeKill(ChatEvent.Kill kill) {
        return kill.killer() + " [" + kill.weapon() + "] " + kill.victim();
    }

    private static int colourOf(Side side) {
        if (side == null) {
            return COLOUR_NEUTRAL;
        }
        return side == Side.CT ? COLOUR_CT : COLOUR_T;
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
