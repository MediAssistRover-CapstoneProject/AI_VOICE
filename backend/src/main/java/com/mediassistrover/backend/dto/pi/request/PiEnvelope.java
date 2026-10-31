package com.mediassistrover.backend.dto.pi.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public record PiEnvelope<T>(@NotBlank String protocolVersion,
                            @NotNull UUID messageId,
                            @NotBlank @Size(max=150) String operationKey,
                            @NotNull UUID correlationId,
                            @NotNull UUID robotId,
                            UUID jobId,
                            UUID jobStepId,
                            @NotBlank @Size(max=100) String messageType,
                            @NotNull Instant occurredAt,
                            @NotNull UUID producerSessionId,
                            @Min(1) long sequenceNo,
                            @NotNull @Valid T payload) { }
