package com.cestats.integration;

import com.cestats.model.Side;
import com.cestats.parse.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchRecordingControllerTest {

    @Test
    @DisplayName("加入房间启动，比赛结算结束自己启动的录制")
    void roomStartsAndMatchFinishStopsOwnedRecording() {
        FakeGateway gateway = new FakeGateway();
        MatchRecordingController controller = new MatchRecordingController(gateway, true);

        controller.accept(new ChatEvent.ContextReset("加入房间"));
        assertTrue(controller.ownsRecording());
        assertEquals(1, gateway.starts);

        controller.onMatchFinished();
        assertFalse(controller.ownsRecording());
        assertEquals(1, gateway.finishes);
        assertFalse(gateway.recording);
    }

    @Test
    @DisplayName("缺少房间消息时，第一条战斗事件作为开始兜底")
    void firstCombatStartsWhenRoomMessageWasMissed() {
        FakeGateway gateway = new FakeGateway();
        MatchRecordingController controller = new MatchRecordingController(gateway, true);

        controller.accept(new ChatEvent.ContextReset("加入匹配队列"));
        controller.accept(new ChatEvent.Kill("killer", Side.CT, "手枪", "victim", Side.T));

        assertTrue(controller.ownsRecording());
        assertEquals(1, gateway.starts);
    }

    @Test
    @DisplayName("客户端还没准备好时会在 tick 中重试启动")
    void retriesStartFromClientTick() {
        FakeGateway gateway = new FakeGateway();
        gateway.failStarts = 1;
        MatchRecordingController controller = new MatchRecordingController(gateway, true);

        controller.accept(new ChatEvent.ContextReset("加入房间"));
        assertFalse(controller.ownsRecording());

        controller.tick();
        assertTrue(controller.ownsRecording());
        assertEquals(2, gateway.starts);
    }

    @Test
    @DisplayName("不会接管或结束用户已经启动的 Flashback 录制")
    void doesNotTakeOwnershipOfExistingRecording() {
        FakeGateway gateway = new FakeGateway();
        gateway.recording = true;
        MatchRecordingController controller = new MatchRecordingController(gateway, true);

        controller.accept(new ChatEvent.ContextReset("加入房间"));
        controller.onMatchFinished();

        assertFalse(controller.ownsRecording());
        assertEquals(0, gateway.starts);
        assertEquals(0, gateway.finishes);
        assertTrue(gateway.recording);
    }

    @Test
    @DisplayName("没有统计表时，比赛结果也会结束录制")
    void resultStopsRecordingWithoutStatsTable() {
        FakeGateway gateway = new FakeGateway();
        MatchRecordingController controller = new MatchRecordingController(gateway, true);

        controller.accept(new ChatEvent.ContextReset("加入房间"));
        controller.accept(new ChatEvent.Result(com.cestats.model.Winner.CT));

        assertFalse(controller.ownsRecording());
        assertEquals(1, gateway.finishes);
    }

    @Test
    @DisplayName("关闭开关时不启动录制")
    void disabledDoesNotStart() {
        FakeGateway gateway = new FakeGateway();
        MatchRecordingController controller = new MatchRecordingController(gateway, false);

        controller.accept(new ChatEvent.ContextReset("加入房间"));
        controller.accept(new ChatEvent.Kill("killer", Side.CT, "手枪", "victim", Side.T));

        assertFalse(controller.ownsRecording());
        assertEquals(0, gateway.starts);
    }

    private static final class FakeGateway implements RecordingGateway {
        private boolean recording;
        private int starts;
        private int finishes;
        private int failStarts;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isRecording() {
            return recording;
        }

        @Override
        public boolean start() {
            starts++;
            if (failStarts > 0) {
                failStarts--;
                return false;
            }
            recording = true;
            return true;
        }

        @Override
        public boolean finish() {
            finishes++;
            recording = false;
            return true;
        }
    }
}
