package com.mediassistrover.backend.controller.audit;

import com.mediassistrover.backend.dto.audit.response.AuditLogResponse;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Audit Logs", description = "Read-only audit trail for ADMIN")
public class AuditLogController {
    private final AuditQueryService service;

    @GetMapping
    @Operation(summary = "Search immutable audit logs")
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.search(actionCode, entityType, accountId, from, to,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))));
    }

    @GetMapping("/{auditLogId}")
    public AuditLogResponse get(@PathVariable UUID auditLogId) { return service.get(auditLogId); }
}
