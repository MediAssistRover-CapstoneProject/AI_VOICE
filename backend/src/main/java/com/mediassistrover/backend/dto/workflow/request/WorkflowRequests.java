package com.mediassistrover.backend.dto.workflow.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

public final class WorkflowRequests {
    private WorkflowRequests() {}
    public record WorkflowUpsertRequest(
            @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{2,79}$") String workflowCode,
            @Positive int versionNo,
            @NotBlank @Size(max=160) String workflowName,
            @NotBlank @Size(max=64) String serviceType,
            @Size(max=4000) String description,
            @NotNull JsonNode inputSchemaJson,
            @NotNull JsonNode inputUiJson,
            @NotNull JsonNode defaultInputsJson,
            @NotNull JsonNode outputMappingJson,
            @NotNull JsonNode requiredCapabilitiesJson,
            @Positive int maxDurationSec,
            @Positive int maxTotalVisits,
            @Positive int graphVersion,
            @Positive int schemaVersion) {}
    public record TaskRequest(@NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{1,79}$") String taskCode,
                              @NotBlank @Size(max=160) String taskName,@Positive int sequenceNo,
                              @Size(max=4000) String description,@NotNull JsonNode configJson) {}
    public record StepRequest(@NotNull UUID taskId,@NotBlank @Size(max=40) String stepTypeCode,
                              @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{1,79}$") String stepCode,
                              @NotBlank @Size(max=160) String stepName,@Positive int sequenceNo,
                              @Size(max=4000) String description,boolean enabled,
                              @NotNull JsonNode paramsJson,@NotNull JsonNode inputMappingJson,
                              @Positive int timeoutSec,@Positive int maxAttempts,@PositiveOrZero int retryDelaySec,
                              @NotNull JsonNode retryCodesJson,@Positive int maxVisits,@NotNull StepAuditLevel auditLevel) {}
    public record TransitionRequest(@NotNull UUID fromStepId,UUID toStepId,@NotNull TransitionOutcome outcome,
                                    JsonNode conditionJson,@Positive int priority,boolean defaultTransition,
                                    TerminalStatus terminalStatus) {}
    public record StartStepRequest(@NotNull UUID startStepId) {}
    public record CloneWorkflowRequest(@Size(max=160) String workflowName) {}
    public record GraphCreateRequest(@NotNull @Valid WorkflowUpsertRequest workflow,
                                     @NotNull @Valid GraphRequest graph) {}
    public record GraphRequest(@NotBlank String startStepCode,
                               @NotEmpty List<@Valid TaskRequest> tasks,
                               @NotEmpty List<@Valid GraphStepRequest> steps,
                               @NotEmpty List<@Valid GraphTransitionRequest> transitions) {}
    public record GraphStepRequest(@NotBlank String taskCode,@NotNull StepType stepType,
            @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{1,79}$") String stepCode,
            @NotBlank @Size(max=160) String stepName,@Positive int sequenceNo,
            @Size(max=4000) String description,@NotNull JsonNode paramsJson,
            @NotNull JsonNode inputMappingJson,@Positive int timeoutSec,@Positive int maxAttempts,
            @PositiveOrZero int retryDelaySec,@NotNull JsonNode retryCodesJson,@Positive int maxVisits,
            @NotNull StepAuditLevel auditLevel) {}
    public record GraphTransitionRequest(@NotBlank String fromStepCode,String toStepCode,
            @NotNull TransitionOutcome outcome,JsonNode conditionJson,@Positive int priority,
            boolean defaultTransition,TerminalStatus terminalStatus) {}
}
