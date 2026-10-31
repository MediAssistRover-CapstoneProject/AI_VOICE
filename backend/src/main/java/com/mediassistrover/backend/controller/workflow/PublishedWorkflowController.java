package com.mediassistrover.backend.controller.workflow;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.PublishedWorkflowSummaryResponse;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.StepTypeResponse;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.WorkflowDetailResponse;
import com.mediassistrover.backend.service.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Tag(name = "Published Workflow Catalog", description = "Read-only workflow catalog for Staff and Admin")
public class PublishedWorkflowController {
    private final WorkflowService service;

    @GetMapping("/step-types")
    public List<StepTypeResponse> stepTypes() {
        return service.stepTypes();
    }

    @GetMapping("/published")
    public PageResponse<PublishedWorkflowSummaryResponse> published(
            @RequestParam(required = false) String serviceType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.published(serviceType, PageRequest.of(page, size,
                Sort.by("workflowCode").ascending().and(Sort.by("versionNo").descending()))));
    }

    @GetMapping("/published/{id}")
    public WorkflowDetailResponse published(@PathVariable UUID id) {
        return service.published(id);
    }
}
