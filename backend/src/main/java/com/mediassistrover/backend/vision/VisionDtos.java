package com.mediassistrover.backend.vision;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class VisionDtos {
    private VisionDtos() { }

    public record DetectionRequest(
            @NotNull UUID eventId,
            @NotNull UUID robotId,
            @NotBlank @Pattern(regexp="SUSPECTED_FALL|GESTURE_SOS|SUSPECTED_SEIZURE") String eventType,
            @NotNull Instant detectedAt,
            @NotBlank @Size(max=100) String algorithm,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
            @Positive Integer personId,
            UUID sessionId,
            @DecimalMin("0.0") Double sampleTimeSec,
            @NotNull JsonNode metrics,
            @NotNull Boolean simulation
    ) { }

    public record DetectionReceipt(
            UUID eventId, boolean accepted, boolean duplicate,
            boolean observationPersisted, boolean simulation,
            UUID incidentId, UUID alertId, Instant receivedAt
    ) { }
}
