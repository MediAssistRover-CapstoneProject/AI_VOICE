package com.mediassistrover.backend.dto.emergency.response;

import com.mediassistrover.backend.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class EmergencyResponses {
    private EmergencyResponses() { }
    public record IncidentResponse(UUID incidentId,UUID robotId,UUID mapId,UUID endpointId,
                                   UUID acknowledgedByAccountId,UUID resolvedByAccountId,String incidentType,
                                   EmergencyIncidentSeverity severity,EmergencyIncidentStatus status,String description,
                                   BigDecimal xM,BigDecimal yM,Instant occurredAt,Instant acknowledgedAt,
                                   Instant resolvedAt,Instant closedAt,long rowVersion) { }
    public record SosResponse(IncidentResponse incident,UUID serviceRequestId,UUID alertId,UUID jobId) { }
    public record DeviceIncidentStatusResponse(UUID incidentId,UUID robotId,UUID mapId,UUID endpointId,
            EmergencyIncidentSeverity severity,EmergencyIncidentStatus status,String description,
            BigDecimal xM,BigDecimal yM,Instant occurredAt,Instant acknowledgedAt,Instant resolvedAt,
            Instant closedAt,UUID serviceRequestId,ServiceRequestStatus requestStatus,
            UUID alertId,AlertStatus alertStatus,UUID jobId,JobStatus jobStatus) { }
}
