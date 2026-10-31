package com.mediassistrover.backend.dto.job.response;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.time.Instant;
import java.util.*;
public final class JobResponses {
    private JobResponses() {}
    public record JobResponse(UUID jobId,UUID workflowId,UUID serviceRequestId,UUID scheduleId,UUID robotId,UUID mapId,
        UUID assignedStaffAccountId,String jobCode,JobStatus status,int priority,JsonNode inputJson,JsonNode contextJson,
        JsonNode resultJson,String workflowHash,int protocolVersion,int schemaVersion,int graphVersion,UUID dispatchCommandId,
        Instant dispatchExpiresAt,Instant queuedAt,Instant acceptedAt,Instant startedAt,UUID cancelCommandId,
        Instant cancelRequestedAt,Instant endedAt,String errorCode,String failureReason,Instant reconciledAt,
        String reconciliationNote,long rowVersion,boolean beOrchestrated,UUID selectedJobStepId) {}
    public record JobTaskResponse(UUID jobTaskId,UUID jobId,UUID taskId,int sequenceNo,String taskNameSnapshot,
        JsonNode configSnapshotJson,JobTaskStatus status,Instant startedAt,Instant endedAt,String errorCode,String errorDetails) {}
    public record JobStepResponse(UUID jobStepId,UUID jobTaskId,UUID stepId,int visitNo,long executionSequenceNo,
        String stepCodeSnapshot,String stepTypeSnapshot,JsonNode resolvedInputsJson,JsonNode resultJson,JobStepStatus status,
        int attemptNo,String operationKey,int timeoutSecSnapshot,Instant startedAt,Instant endedAt,String errorCode,JsonNode errorDetailsJson,long rowVersion,Instant selectedAt,
        String interruptionReason,Instant interruptionRequestedAt) {}
    public record JobEventResponse(UUID jobEventId,UUID jobId,UUID robotId,UUID jobTaskId,UUID jobStepId,UUID actorAccountId,
        JobEventProducerType producerType,UUID producerSessionId,long sequenceNo,String eventType,String correlationKey,
        Integer attemptNo,String previousStatus,String newStatus,String statusScope,JsonNode eventDataJson,
        Instant occurredAt,Instant receivedAt,Instant persistedAt) {}
    public record JobDetailResponse(JobResponse job,List<JobTaskResponse> tasks,List<JobStepResponse> steps) {}
}
