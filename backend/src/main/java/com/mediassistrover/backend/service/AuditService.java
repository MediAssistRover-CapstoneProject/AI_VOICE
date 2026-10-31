package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.Account;

import java.util.Map;
import java.util.UUID;

public interface AuditService {
    void record(Account actor, String actionCode, String entityType, UUID entityId,
                Map<String, Object> beforeData, Map<String, Object> afterData,
                RequestMetadata metadata);
}
