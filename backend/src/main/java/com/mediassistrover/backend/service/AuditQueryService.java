package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.audit.response.AuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface AuditQueryService {
    Page<AuditLogResponse> search(String actionCode, String entityType, UUID accountId,
                                  Instant from, Instant to, Pageable pageable);
    AuditLogResponse get(UUID auditLogId);
}
