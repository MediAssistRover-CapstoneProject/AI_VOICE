package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.monitoring.request.MonitoringRequests.*;
import com.mediassistrover.backend.dto.monitoring.response.MonitoringResponses.*;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.response.PiResponses.AckResponse;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.UUID;
public interface MonitoringService {
    AckResponse recordDetection(PiEnvelope<DetectionPayload> envelope,DevicePrincipal principal);
    AckResponse recordCrowdDensity(PiEnvelope<CrowdDensityPayload> envelope,DevicePrincipal principal);
    DetectionResponse getDetection(UUID id);
    Page<DetectionResponse> listDetections(UUID robotId,DetectionEventType type,Instant from,Instant to,Pageable pageable);
    CrowdDensityResponse getCrowdDensity(UUID id);
    Page<CrowdDensityResponse> listCrowdDensity(UUID zoneId,CrowdDensityLevel level,Instant from,Instant to,Pageable pageable);
    HeatmapResponse createHeatmap(HeatmapCreate request,UUID actorId,RequestMetadata metadata);
    HeatmapResponse getHeatmap(UUID id);
    Page<HeatmapResponse> listHeatmaps(UUID mapId,String type,Pageable pageable);
}
