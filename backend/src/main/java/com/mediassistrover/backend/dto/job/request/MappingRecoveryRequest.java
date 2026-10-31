package com.mediassistrover.backend.dto.job.request;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record MappingRecoveryRequest(
    @NotNull UUID jobStepId,
    @NotNull UUID transportSessionId,
    @NotNull @AssertTrue Boolean confirmedStopped,
    @NotNull JobRequests.ReconcileOutcome outcome,
    @NotBlank @Size(max = 2000) String note) {}
