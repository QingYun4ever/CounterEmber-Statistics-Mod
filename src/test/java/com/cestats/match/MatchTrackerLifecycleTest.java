package com.cestats.match;

import com.cestats.parse.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MatchTrackerLifecycleTest {

    @Test
    @DisplayName("把房间、战斗和长间隔边界通知给生命周期监听器")
    void emitsRecordingBoundaries() {
        List<ChatEvent> events = new ArrayList<>();
        MatchTracker tracker = new MatchTracker(ignored -> {
        }, events::add);

        long start = 1_000_000L;
        tracker.setContext("server", "self");
        tracker.accept("你已加入房间。", start);
        tracker.accept("[CT] killer 手枪 ☠ [T] victim", start + 1);
        tracker.accept("[CT] next 手枪 ☠ [T] other",
                start + MatchTracker.CONTEXT_GAP_MS + 2);

        assertInstanceOf(ChatEvent.ContextReset.class, events.get(0));
        assertEquals("加入房间", ((ChatEvent.ContextReset) events.get(0)).why());
        assertInstanceOf(ChatEvent.Kill.class, events.get(1));
        assertInstanceOf(ChatEvent.ContextReset.class, events.get(2));
        assertEquals("战斗间隔超时", ((ChatEvent.ContextReset) events.get(2)).why());
        assertInstanceOf(ChatEvent.Kill.class, events.get(3));
    }
}
