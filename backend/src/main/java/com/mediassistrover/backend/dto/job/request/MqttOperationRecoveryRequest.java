package com.mediassistrover.backend.dto.job.request;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record MqttOperationRecoveryRequest(
        @NotNull UUID commandId,
        @NotBlank @Size(max=150) String operationKey,
        @NotNull UUID transportSessionId,
        @NotNull Evidence evidence,
        @NotNull @AssertTrue Boolean confirmedStopped,
        @NotNull JobRequests.ReconcileOutcome outcome,
        @NotBlank @Size(max=2000) String note) {
    public enum Evidence { PI_STOP_CONFIRMED, STAFF_INSPECTED_STOP }
}
