package com.mediassistrover.backend.dto.report.response;

import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.enums.RobotStatus;
import com.mediassistrover.backend.enums.ServiceRequestType;

import java.time.Instant;
import java.util.Map;

public record OperationsOverviewResponse(
        Instant generatedAt,
        Map<JobStatus, Long> jobsByStatus,
        Map<RobotStatus, Long> robotsByStatus,
        Map<ServiceRequestType, Long> requestsByType,
        long openAlerts,
        long activeEmergencyIncidents,
        long terminalJobs,
        long succeededJobs,
        double jobCompletionRatePercent
) { }
