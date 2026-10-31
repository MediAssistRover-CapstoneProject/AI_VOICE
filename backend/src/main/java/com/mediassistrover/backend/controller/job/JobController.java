package com.mediassistrover.backend.controller.job;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.job.request.JobRequests.*;
import com.mediassistrover.backend.dto.job.response.JobResponses.*;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.JobService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.time.Instant;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.dto.job.response.WorkflowEventResponse;
@RestController @RequestMapping("/api/v1/jobs") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Jobs")
public class JobController {
    private final com.mediassistrover.backend.service.JobUiEventStream uiEvents;
    @GetMapping(value="/{id}/stream",produces=org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor){return uiEvents.subscribe(id,actor);}
    private final JobService service; private final com.mediassistrover.backend.service.WorkflowEventService workflowEventService;
    @GetMapping public PageResponse<JobResponse> list(@RequestParam(required=false)JobStatus status,@RequestParam(required=false)UUID workflowId,@RequestParam(required=false)UUID requestId,@RequestParam(required=false)UUID robotId,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,@AuthenticationPrincipal AccountPrincipal a){return PageResponse.from(service.list(status,workflowId,requestId,robotId,from,to,a,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"queuedAt"))));}
    @GetMapping("/{id}") public JobDetailResponse get(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a){return service.get(id,a);}
    @GetMapping("/{id}/events") public PageResponse<JobEventResponse> events(@PathVariable UUID id,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="50")@Min(1)@Max(200)int size,@AuthenticationPrincipal AccountPrincipal a){return PageResponse.from(service.events(id,a,PageRequest.of(page,size,Sort.by(Sort.Direction.ASC,"occurredAt"))));}
    @PostMapping("/{id}/cancel") public JobResponse cancel(@PathVariable UUID id,@Valid @RequestBody CancelJobRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.cancel(id,b,a,RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/reconcile") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public JobResponse reconcile(@PathVariable UUID id,@Valid @RequestBody ReconcileJobRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.reconcile(id,b,a,RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/events") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public WorkflowEventResponse publishEvent(@PathVariable UUID id,@Valid @RequestBody WorkflowEventRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return workflowEventService.publish(id,b,a,RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/assign-robot") @PreAuthorize("hasRole('ADMIN')") public JobResponse assignRobot(@PathVariable UUID id,@Valid @RequestBody AssignRobotRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.assignRobot(id,b.robotId(),a,RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/assign-staff") @PreAuthorize("hasRole('ADMIN')") public JobResponse assignStaff(@PathVariable UUID id,@Valid @RequestBody AssignStaffRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.assignStaff(id,b.staffAccountId(),a,RequestMetadataFactory.from(r));}
}
