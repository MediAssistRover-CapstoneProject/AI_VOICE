package com.mediassistrover.backend.controller.workflow;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.workflow.request.WorkflowRequests.*;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.*;
import com.mediassistrover.backend.enums.WorkflowStatus;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.WorkflowService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/admin/workflows") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Workflow Definitions",description="ADMIN quản lý definition; STAFF đọc published")
public class WorkflowController {
    private final WorkflowService service;
    @PostMapping("/graph") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Create Workflow and complete graph atomically")
    public WorkflowDetailResponse createGraph(@Valid @RequestBody GraphCreateRequest body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.createGraph(body,actor.accountId(),RequestMetadataFactory.from(request));}
    @PutMapping("/{id}/graph") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Replace draft graph atomically; rollback on validation failure")
    public WorkflowDetailResponse replaceGraph(@PathVariable UUID id,@Valid @RequestBody GraphRequest body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.replaceGraph(id,body,actor.accountId(),RequestMetadataFactory.from(request));}
    @GetMapping("/step-types") @Operation(deprecated=true) public List<StepTypeResponse> stepTypes(){return service.stepTypes();}
    @GetMapping @PreAuthorize("hasRole('ADMIN')") public PageResponse<WorkflowResponse> list(@RequestParam(required=false)WorkflowStatus status,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.list(status,PageRequest.of(page,size,Sort.by("workflowCode").and(Sort.by(Sort.Direction.DESC,"versionNo")))));}
    @GetMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public WorkflowDetailResponse get(@PathVariable UUID id){return service.get(id);}
    @GetMapping("/published/all") @Operation(deprecated=true) public List<WorkflowDetailResponse> published(){return service.published();}
    @GetMapping("/published/{id}") @Operation(deprecated=true) public WorkflowDetailResponse published(@PathVariable UUID id){return service.published(id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public WorkflowResponse create(@Valid @RequestBody WorkflowUpsertRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.create(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public WorkflowResponse update(@PathVariable UUID id,@Valid @RequestBody WorkflowUpsertRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.update(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')") public void delete(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){service.deleteDraft(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/tasks") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public TaskResponse addTask(@PathVariable UUID id,@Valid @RequestBody TaskRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.addTask(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/{id}/tasks/{taskId}") @PreAuthorize("hasRole('ADMIN')") public TaskResponse updateTask(@PathVariable UUID id,@PathVariable UUID taskId,@Valid @RequestBody TaskRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateTask(id,taskId,b,a.accountId(),RequestMetadataFactory.from(r));}
    @DeleteMapping("/{id}/tasks/{taskId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')") public void deleteTask(@PathVariable UUID id,@PathVariable UUID taskId,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){service.deleteTask(id,taskId,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/steps") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public StepResponse addStep(@PathVariable UUID id,@Valid @RequestBody StepRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.addStep(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/{id}/steps/{stepId}") @PreAuthorize("hasRole('ADMIN')") public StepResponse updateStep(@PathVariable UUID id,@PathVariable UUID stepId,@Valid @RequestBody StepRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateStep(id,stepId,b,a.accountId(),RequestMetadataFactory.from(r));}
    @DeleteMapping("/{id}/steps/{stepId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')") public void deleteStep(@PathVariable UUID id,@PathVariable UUID stepId,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){service.deleteStep(id,stepId,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/transitions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public TransitionResponse addTransition(@PathVariable UUID id,@Valid @RequestBody TransitionRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.addTransition(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/{id}/transitions/{transitionId}") @PreAuthorize("hasRole('ADMIN')") public TransitionResponse updateTransition(@PathVariable UUID id,@PathVariable UUID transitionId,@Valid @RequestBody TransitionRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateTransition(id,transitionId,b,a.accountId(),RequestMetadataFactory.from(r));}
    @DeleteMapping("/{id}/transitions/{transitionId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')") public void deleteTransition(@PathVariable UUID id,@PathVariable UUID transitionId,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){service.deleteTransition(id,transitionId,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/{id}/start-step") @PreAuthorize("hasRole('ADMIN')") public WorkflowResponse start(@PathVariable UUID id,@Valid @RequestBody StartStepRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setStartStep(id,b.startStepId(),a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/validate") @PreAuthorize("hasRole('ADMIN')") @Operation(summary="Validate và compile graph") public WorkflowValidationResponse validate(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.validate(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/publish") @PreAuthorize("hasRole('ADMIN')") public WorkflowResponse publish(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.publish(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/archive") @PreAuthorize("hasRole('ADMIN')") public WorkflowResponse archive(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.archive(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/clone") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public WorkflowDetailResponse clone(@PathVariable UUID id,@Valid @RequestBody CloneWorkflowRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.cloneVersion(id,b.workflowName(),a.accountId(),RequestMetadataFactory.from(r));}
}
