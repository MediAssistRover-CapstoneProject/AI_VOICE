package com.mediassistrover.backend.dto.voice.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.VoiceCommandStatus;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class VoiceResponses {
    private VoiceResponses() { }

    public record InteractionResponse(
            UUID voiceInteractionId,
            UUID accountId,
            UUID robotId,
            String languageCode,
            VoiceInteractionStatus status,
            Instant startedAt,
            Instant endedAt,
            String detectedIntent,
            String transcriptSummary,
            String audioUri
    ) { }

    public record InteractionMetadataResponse(
            UUID voiceInteractionId,
            UUID robotId,
            String languageCode,
            VoiceInteractionStatus status,
            Instant startedAt,
            Instant endedAt,
            String detectedIntent,
            boolean rawDataAvailable
    ) { }

    public record CommandResponse(
            UUID voiceCommandId,
            UUID voiceInteractionId,
            UUID serviceRequestId,
            int sequenceNo,
            String rawText,
            String normalizedText,
            String intentCode,
            BigDecimal confidenceScore,
            String commandType,
            JsonNode parameters,
            VoiceCommandStatus status,
            Instant receivedAt,
            Instant processedAt
    ) { }

    public record CommandMetadataResponse(
            UUID voiceCommandId,
            UUID voiceInteractionId,
            UUID serviceRequestId,
            int sequenceNo,
            String intentCode,
            BigDecimal confidenceScore,
            String commandType,
            VoiceCommandStatus status,
            Instant receivedAt,
            Instant processedAt,
            boolean rawDataAvailable
    ) { }
}
