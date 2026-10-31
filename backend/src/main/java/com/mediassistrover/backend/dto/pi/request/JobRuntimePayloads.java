package com.mediassistrover.backend.dto.pi.request;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.JobStepStatus;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
public final class JobRuntimePayloads {
    private JobRuntimePayloads() {}
    public record CommandAckPayload(boolean accepted,@Size(max=1000) String reason) {}
    public record JobStartedPayload(JsonNode runtimeContext) {}
    public record JobResultPayload(@NotNull JsonNode result) {}
    public record JobErrorPayload(@NotBlank @Size(max=100) String errorCode,@NotBlank @Size(max=4000) String failureReason,JsonNode details) {}
    public record StepStartedPayload(@Min(1) int attemptNo) {}
    public record StepResultPayload(@NotNull @Schema(description="Strict Step schema v2 result. Root fields depend on the fixed StepType; unknown root fields are rejected.") JsonNode result) {}
    public record StepErrorPayload(@NotBlank @Size(max=100) String errorCode,JsonNode details,boolean retryable) {}
    public record StepServiceExecutePayload(JsonNode input) {}
    public record CreateStepVisitPayload(@NotNull java.util.UUID previousJobStepId) {}
}
