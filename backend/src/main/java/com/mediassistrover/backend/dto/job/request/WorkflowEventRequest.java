package com.mediassistrover.backend.dto.job.request;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
public record WorkflowEventRequest(@NotNull UUID eventId,@NotBlank @Size(max=100) String eventType,
                                   @NotBlank @Size(max=150) String correlationKey,@NotNull Instant occurredAt,
                                   JsonNode data) { }
