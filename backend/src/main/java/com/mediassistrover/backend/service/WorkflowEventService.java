package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.dto.job.response.WorkflowEventResponse;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.security.AccountPrincipal;
import java.util.UUID;
public interface WorkflowEventService {
    WorkflowEventResponse publish(UUID jobId,WorkflowEventRequest request,AccountPrincipal actor,RequestMetadata metadata);
    boolean consumeAvailable(JobStep waitStep);
    void publishAlertAcknowledged(com.mediassistrover.backend.entity.Alert alert,com.mediassistrover.backend.entity.Account actor,java.time.Instant occurredAt,long acknowledged,long total);
}
