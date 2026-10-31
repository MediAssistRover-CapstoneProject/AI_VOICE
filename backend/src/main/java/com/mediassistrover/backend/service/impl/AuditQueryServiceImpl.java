package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.audit.response.AuditLogResponse;
import com.mediassistrover.backend.entity.AuditLog;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.repository.AuditLogRepository;
import com.mediassistrover.backend.service.AuditQueryService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditQueryServiceImpl implements AuditQueryService {
    private final AuditLogRepository repository;

    @Override
    public Page<AuditLogResponse> search(String actionCode, String entityType, UUID accountId,
                                         Instant from, Instant to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessRuleException("Audit from must not be after to");
        }
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(actionCode)) {
                predicates.add(cb.equal(cb.lower(root.get("actionCode")), actionCode.trim().toLowerCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(entityType)) {
                predicates.add(cb.equal(cb.lower(root.get("entityType")), entityType.trim().toLowerCase(Locale.ROOT)));
            }
            if (accountId != null) predicates.add(cb.equal(root.get("account").get("accountId"), accountId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    public AuditLogResponse get(UUID auditLogId) {
        return toResponse(repository.findById(auditLogId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog not found: " + auditLogId)));
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(log.getAuditLogId(),
                log.getAccount() == null ? null : log.getAccount().getAccountId(),
                log.getAccount() == null ? null : log.getAccount().getUsername(),
                log.getActionCode(), log.getEntityType(), log.getEntityId(),
                log.getBeforeData(), log.getAfterData(), log.getIpAddress(), log.getUserAgent(),
                log.getCorrelationId(), log.getOccurredAt());
    }
}
