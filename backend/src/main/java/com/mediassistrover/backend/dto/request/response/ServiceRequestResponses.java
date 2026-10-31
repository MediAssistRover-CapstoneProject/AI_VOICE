package com.mediassistrover.backend.dto.request.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

public final class ServiceRequestResponses {
    private ServiceRequestResponses() {}
    public record ServiceRequestResponse(UUID serviceRequestId,UUID requestedByAccountId,UUID requestedRobotId,
            String requestCode,ServiceRequestType requestType,RequestSourceType sourceType,int priority,
            ServiceRequestStatus status,String requestNote,JsonNode requestDataJson,String idempotencyKey,
            Instant requestedAt,Instant scheduledAt,Instant cancelledAt,Instant completedAt,long rowVersion,Instant updatedAt) {}
    public record NavigationResponse(ServiceRequestResponse request,UUID mapId,UUID workflowId,UUID roomId,UUID originEndpointId,UUID destinationEndpointId,
            UUID destinationGroupId,UUID resolvedEndpointId,TargetMode targetMode,NavigationMode navigationMode,
            String accessibilityNeed,int passengerCount,String arrivalInstruction) {}
    public record EscortResponse(ServiceRequestResponse request,UUID mapId,UUID originRoomId,UUID destinationRoomId,
            UUID originEndpointId,UUID destinationEndpointId,UUID destinationGroupId,UUID resolvedEndpointId,
            TargetMode targetMode,String patientReference,EscortMode escortMode,BigDecimal walkingSpeedMps,
            int confirmationTimeoutSec,String escortNote) {}
    public record DeviceJobSummary(UUID jobId,String jobCode,JobStatus status,UUID robotId,
            String errorCode,String failureReason,Instant queuedAt,Instant startedAt,Instant endedAt) {}
    public record DeviceRequestDetailResponse(UUID serviceRequestId,String requestCode,
            ServiceRequestType requestType,ServiceRequestStatus status,int priority,String requestNote,
            Instant requestedAt,Instant cancelledAt,Instant completedAt,List<DeviceJobSummary> jobs) {}
}
