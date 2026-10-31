package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.StepServiceExecutePayload;
import com.mediassistrover.backend.dto.pi.response.PiResponses.StepServiceExecutionResponse;
import com.mediassistrover.backend.security.DevicePrincipal;
import java.util.UUID;
public interface PiStepService {
    StepServiceExecutionResponse execute(PiEnvelope<StepServiceExecutePayload> envelope,DevicePrincipal principal);
    StepServiceExecutionResponse result(UUID jobStepId,DevicePrincipal principal);
}
