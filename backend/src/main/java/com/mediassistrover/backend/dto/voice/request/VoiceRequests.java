package com.mediassistrover.backend.dto.voice.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.VoiceCommandStatus;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class VoiceRequests {
    private VoiceRequests() { }

    public record InteractionStartPayload(
            @NotBlank @Size(max = 16) @Pattern(regexp = "[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*") String languageCode,
            @NotNull Instant startedAt
    ) { }

    public record InteractionCompletionPayload(
            @NotNull VoiceInteractionStatus status,
            @NotNull Instant endedAt,
            @Size(max = 80) String detectedIntent,
            @Size(max = 10000) String transcriptSummary,
            @Size(max = 4000) String audioUri
    ) { }

    public record CommandPayload(
            @NotNull UUID voiceInteractionId,
            UUID serviceRequestId,
            @Min(1) int sequenceNo,
            @NotBlank @Size(max = 10000) String rawText,
            @Size(max = 10000) String normalizedText,
            @Size(max = 80) String intentCode,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceScore,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]*") String commandType,
            @NotNull JsonNode parameters,
            @NotNull VoiceCommandStatus status,
            @NotNull Instant receivedAt,
            Instant processedAt
    ) { }

    public record CommandResultPayload(
            @NotNull VoiceCommandStatus status,
            UUID serviceRequestId,
            @NotNull Instant processedAt
    ) { }
}
