package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.*;
import com.mediassistrover.backend.dto.pi.response.PiResponses.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import java.util.*;
public interface PiJobRuntimeService {
    Optional<JobCommandResponse> nextCommand(DevicePrincipal principal);
    AckResponse commandAck(UUID commandId,PiEnvelope<CommandAckPayload> envelope,DevicePrincipal principal);
    AckResponse jobStarted(UUID jobId,PiEnvelope<JobStartedPayload> envelope,DevicePrincipal principal);
    AckResponse jobResult(UUID jobId,PiEnvelope<JobResultPayload> envelope,DevicePrincipal principal);
    AckResponse jobError(UUID jobId,PiEnvelope<JobErrorPayload> envelope,DevicePrincipal principal);
    AckResponse stepStarted(UUID stepId,PiEnvelope<StepStartedPayload> envelope,DevicePrincipal principal);
    AckResponse stepResult(UUID stepId,PiEnvelope<StepResultPayload> envelope,DevicePrincipal principal);
    AckResponse stepError(UUID stepId,PiEnvelope<StepErrorPayload> envelope,DevicePrincipal principal);
    JobStepCommand createStepVisit(UUID jobId,UUID stepId,PiEnvelope<CreateStepVisitPayload> envelope,DevicePrincipal principal);
}
