package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.workflow.request.WorkflowRequests.*;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.*;
import com.mediassistrover.backend.enums.WorkflowStatus;
import org.springframework.data.domain.*;
import java.util.*;

public interface WorkflowService {
    WorkflowDetailResponse createGraph(GraphCreateRequest request,UUID actorId,RequestMetadata metadata);
    WorkflowDetailResponse replaceGraph(UUID id,GraphRequest request,UUID actorId,RequestMetadata metadata);
    List<StepTypeResponse> stepTypes();
    WorkflowResponse create(WorkflowUpsertRequest request,UUID actorId,RequestMetadata metadata);
    WorkflowResponse update(UUID id,WorkflowUpsertRequest request,UUID actorId,RequestMetadata metadata);
    WorkflowDetailResponse get(UUID id);
    Page<WorkflowResponse> list(WorkflowStatus status,Pageable pageable);
    List<WorkflowDetailResponse> published();
    Page<PublishedWorkflowSummaryResponse> published(String serviceType,Pageable pageable);
    WorkflowDetailResponse published(UUID id);
    TaskResponse addTask(UUID workflowId,TaskRequest request,UUID actorId,RequestMetadata metadata);
    TaskResponse updateTask(UUID workflowId,UUID taskId,TaskRequest request,UUID actorId,RequestMetadata metadata);
    void deleteTask(UUID workflowId,UUID taskId,UUID actorId,RequestMetadata metadata);
    StepResponse addStep(UUID workflowId,StepRequest request,UUID actorId,RequestMetadata metadata);
    StepResponse updateStep(UUID workflowId,UUID stepId,StepRequest request,UUID actorId,RequestMetadata metadata);
    void deleteStep(UUID workflowId,UUID stepId,UUID actorId,RequestMetadata metadata);
    TransitionResponse addTransition(UUID workflowId,TransitionRequest request,UUID actorId,RequestMetadata metadata);
    TransitionResponse updateTransition(UUID workflowId,UUID transitionId,TransitionRequest request,UUID actorId,RequestMetadata metadata);
    void deleteTransition(UUID workflowId,UUID transitionId,UUID actorId,RequestMetadata metadata);
    WorkflowResponse setStartStep(UUID workflowId,UUID stepId,UUID actorId,RequestMetadata metadata);
    WorkflowValidationResponse validate(UUID id,UUID actorId,RequestMetadata metadata);
    WorkflowResponse publish(UUID id,UUID actorId,RequestMetadata metadata);
    WorkflowResponse archive(UUID id,UUID actorId,RequestMetadata metadata);
    WorkflowDetailResponse cloneVersion(UUID id,String name,UUID actorId,RequestMetadata metadata);
    void deleteDraft(UUID id,UUID actorId,RequestMetadata metadata);
}
