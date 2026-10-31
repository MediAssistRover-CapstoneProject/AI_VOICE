package com.mediassistrover.backend.dto.job.response;
import java.time.Instant;
import java.util.UUID;
public record WorkflowEventResponse(UUID eventId,UUID jobId,String eventType,String correlationKey,
                                    boolean duplicate,UUID consumedByJobStepId,Instant consumedAt) { }
