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
}
