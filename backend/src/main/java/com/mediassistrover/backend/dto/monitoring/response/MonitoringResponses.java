package com.mediassistrover.backend.dto.monitoring.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MonitoringResponses {
    private MonitoringResponses() { }
    public record DetectionResponse(UUID detectionEventId,UUID robotId,UUID mapId,UUID zoneId,
            DetectionEventType eventType,BigDecimal confidenceScore,BigDecimal xM,BigDecimal yM,
            DetectionLocationBasis locationBasis,String modelVersion,JsonNode eventPayload,String evidenceRef,
            Instant detectedAt,Instant receivedAt) { }
    public record CrowdDensityResponse(UUID crowdDensityRecordId,UUID robotId,UUID zoneId,UUID checkpointId,
            UUID jobStepId,int personCount,CrowdDensityLevel densityLevel,BigDecimal confidenceScore,
            String modelVersion,JsonNode metrics,String evidenceRef,Instant capturedAt,Instant recordedAt) { }
    public record HeatmapResponse(UUID heatmapRecordId,UUID mapId,String heatmapType,Instant periodStart,
            Instant periodEnd,BigDecimal cellSizeM,JsonNode gridData,String imageUri,BigDecimal maxValue,
            int sourceRecordCount,Instant generatedAt) { }
}
