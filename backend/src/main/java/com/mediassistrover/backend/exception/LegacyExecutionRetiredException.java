package com.mediassistrover.backend.exception;

public class LegacyExecutionRetiredException extends RuntimeException {
    public LegacyExecutionRetiredException() {
        super("Legacy Pi execution REST APIs have been retired; use MQTT MAR-STEP-2. Existing Job transport cannot be changed.");
    }

    public LegacyExecutionRetiredException(String message) {
        super(message);
    }
}
