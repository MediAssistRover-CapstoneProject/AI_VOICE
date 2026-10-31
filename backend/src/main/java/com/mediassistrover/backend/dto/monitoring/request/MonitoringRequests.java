package com.mediassistrover.backend.dto.monitoring.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MonitoringRequests {
    private MonitoringRequests() { }

    public record DetectionPayload(
            @NotNull UUID mapId,
            UUID zoneId,
            @NotNull DetectionEventType eventType,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceScore,
            BigDecimal xM,
            BigDecimal yM,
            @NotNull DetectionLocationBasis locationBasis,
            @Size(max=100) String modelVersion,
            @NotNull JsonNode eventPayload,
            @Size(max=4000) String evidenceRef,
            @NotNull Instant detectedAt
    ) { }

    public record CrowdDensityPayload(
            @NotNull UUID zoneId,
            UUID checkpointId,
            @PositiveOrZero int personCount,
            CrowdDensityLevel densityLevel,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceScore,
            @Size(max=100) String modelVersion,
            @NotNull JsonNode metrics,
            @Size(max=4000) String evidenceRef,
            @NotNull Instant capturedAt
    ) { }

    public record HeatmapCreate(
            @NotNull UUID mapId,
            @NotBlank @Size(max=64) @Pattern(regexp="[A-Z0-9][A-Z0-9_-]*") String heatmapType,
            @NotNull Instant periodStart,
            @NotNull Instant periodEnd,
            @NotNull @DecimalMin(value="0.0",inclusive=false) BigDecimal cellSizeM,
            @NotNull JsonNode gridData,
            @Size(max=4000) String imageUri,
            @DecimalMin("0.0") BigDecimal maxValue,
            @PositiveOrZero int sourceRecordCount
    ) { }
}
