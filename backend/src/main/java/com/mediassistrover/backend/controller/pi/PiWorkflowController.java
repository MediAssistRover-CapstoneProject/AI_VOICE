package com.mediassistrover.backend.controller.pi;

import com.mediassistrover.backend.config.OpenApiConfig;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.*;
import com.mediassistrover.backend.service.WorkflowService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/pi/workflows") @RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')") @SecurityRequirement(name=OpenApiConfig.DEVICE_BEARER_SCHEME)
@Tag(name="Server–Pi Workflow Sync")
public class PiWorkflowController {
    private final WorkflowService service;
    @GetMapping public List<WorkflowDetailResponse> published(){return service.published();}
    @GetMapping("/{id}") public WorkflowDetailResponse get(@PathVariable UUID id){WorkflowDetailResponse result=service.get(id);if(result.workflow().status()!=com.mediassistrover.backend.enums.WorkflowStatus.PUBLISHED||!result.workflow().acceptsNewJobs())throw new com.mediassistrover.backend.exception.ResourceNotFoundException("Published workflow not found: "+id);return result;}
}
