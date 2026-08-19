package com.cestats;

import com.cestats.ping.PingClickDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PingClickDetectorTest {

    @Test
    @DisplayName("单击先记住，超过窗口后提交普通标点")
    void singleClickExpiresAsPending() {
        PingClickDetector detector = new PingClickDetector(280);

        assertEquals(PingClickDetector.ClickResult.PENDING, detector.registerClick(1_000));
        assertEquals(false, detector.isPendingExpired(1_280));
        assertEquals(true, detector.isPendingExpired(1_281));
    }

    @Test
    @DisplayName("窗口内第二次点击升级为警告，并开始新序列")
    void quickSecondClickIsWarning() {
        PingClickDetector detector = new PingClickDetector(280);

        assertEquals(PingClickDetector.ClickResult.PENDING, detector.registerClick(1_000));
        assertEquals(PingClickDetector.ClickResult.WARNING, detector.registerClick(1_200));
        assertEquals(false, detector.isPendingExpired(2_000));
        assertEquals(PingClickDetector.ClickResult.PENDING, detector.registerClick(2_000));
    }

    @Test
    @DisplayName("窗口外第二次点击不会误判为警告")
    void slowSecondClickStartsNewSequence() {
        PingClickDetector detector = new PingClickDetector(280);

        detector.registerClick(1_000);
        assertEquals(PingClickDetector.ClickResult.PENDING, detector.registerClick(1_281));
        assertEquals(true, detector.isPendingExpired(1_562));
    }

    @Test
    @DisplayName("双击窗口必须为正数")
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new PingClickDetector(0));
        assertThrows(IllegalArgumentException.class, () -> new PingClickDetector(-1));
    }
}
