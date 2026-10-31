package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.entity.AuditLog;
import com.mediassistrover.backend.repository.AuditLogRepository;
import com.mediassistrover.backend.service.AuditService;
import com.mediassistrover.backend.service.RequestMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Account actor, String actionCode, String entityType, UUID entityId,
                       Map<String, Object> beforeData, Map<String, Object> afterData,
                       RequestMetadata metadata) {
        RequestMetadata safeMetadata = metadata == null ? new RequestMetadata(null, null, null) : metadata;
        auditLogRepository.save(AuditLog.builder()
                .account(actor)
                .actionCode(actionCode)
                .entityType(entityType)
                .entityId(entityId)
                .beforeData(beforeData)
                .afterData(afterData)
                .ipAddress(safeMetadata.ipAddress())
                .userAgent(safeMetadata.userAgent())
                .correlationId(safeMetadata.correlationId())
                .occurredAt(Instant.now(clock))
                .build());
    }
}
