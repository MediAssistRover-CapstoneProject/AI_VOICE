package com.mediassistrover.backend.dto.audit.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID auditLogId,
        UUID accountId,
        String actorUsername,
        String actionCode,
        String entityType,
        UUID entityId,
        Map<String, Object> beforeData,
        Map<String, Object> afterData,
        String ipAddress,
        String userAgent,
        String correlationId,
        Instant occurredAt
) { }
