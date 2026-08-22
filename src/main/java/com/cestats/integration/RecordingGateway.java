package com.cestats.integration;

/**
 * Small abstraction around an optional replay recorder so the match lifecycle remains
 * unit-testable without loading Minecraft or Flashback.
 */
public interface RecordingGateway {

    boolean isAvailable();

    boolean isRecording();

    boolean start();

    boolean finish();

    /** Whether this gateway resolved a timeline-marker entry point; marking is optional. */
    boolean supportsMarks();

    /**
     * Places a marker on the replay timeline at whatever position the recorder is currently
     * writing. There is no way to backdate one, so callers must mark as the event arrives.
     *
     * @param description shown as a tooltip when hovering the marker
     * @param colour      standard {@code 0xRRGGBB}
     */
    boolean mark(String description, int colour);
}
