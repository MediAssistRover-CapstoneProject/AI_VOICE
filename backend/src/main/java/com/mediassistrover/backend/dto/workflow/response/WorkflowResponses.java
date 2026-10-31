package com.mediassistrover.backend.dto.workflow.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.time.Instant;
import java.util.*;

public final class WorkflowResponses {
    private WorkflowResponses() {}
    public record StepTypeResponse(String stepTypeCode,String displayName,StepExecutorKind executorKind) {}
    public record WorkflowResponse(UUID workflowId,UUID startStepId,UUID publishedByAccountId,String workflowCode,
                                   int versionNo,String workflowName,String serviceType,String description,
                                   JsonNode inputSchemaJson,JsonNode inputUiJson,JsonNode defaultInputsJson,
                                   JsonNode outputMappingJson,JsonNode requiredCapabilitiesJson,int maxDurationSec,
                                   int maxTotalVisits,int graphVersion,int schemaVersion,String definitionHash,
                                   WorkflowStatus status,boolean acceptsNewJobs,Instant publishedAt,long rowVersion,
                                   Instant createdAt,Instant updatedAt) {}
    public record PublishedWorkflowSummaryResponse(UUID workflowId,String workflowCode,int versionNo,
                                                    String workflowName,String serviceType,String description,
                                                    JsonNode requiredCapabilitiesJson,int maxDurationSec,
                                                    int graphVersion,int schemaVersion,Instant publishedAt) {}
    public record TaskResponse(UUID taskId,UUID workflowId,String taskCode,String taskName,int sequenceNo,
                               String description,JsonNode configJson,Instant createdAt) {}
    public record StepResponse(UUID stepId,UUID workflowId,UUID taskId,String stepTypeCode,String stepCode,
                               String stepName,int sequenceNo,String description,boolean enabled,JsonNode paramsJson,
                               JsonNode inputMappingJson,int timeoutSec,int maxAttempts,int retryDelaySec,
                               JsonNode retryCodesJson,int maxVisits,StepAuditLevel auditLevel,Instant createdAt) {}
    public record TransitionResponse(UUID transitionId,UUID workflowId,UUID fromStepId,UUID toStepId,
                                     TransitionOutcome outcome,JsonNode conditionJson,int priority,
                                     boolean defaultTransition,TerminalStatus terminalStatus) {}
    public record WorkflowDetailResponse(WorkflowResponse workflow,List<TaskResponse> tasks,List<StepResponse> steps,
                                         List<TransitionResponse> transitions,JsonNode compiledDefinitionJson) {}
    public record WorkflowValidationResponse(UUID workflowId,WorkflowStatus status,String definitionHash,
                                             int taskCount,int stepCount,int transitionCount,List<String> warnings) {}
}
