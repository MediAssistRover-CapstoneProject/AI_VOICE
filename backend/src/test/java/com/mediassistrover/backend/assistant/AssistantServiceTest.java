package com.mediassistrover.backend.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.VoiceAssistantTurn;
import com.mediassistrover.backend.entity.VoiceInteraction;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.EmergencyService;
import com.mediassistrover.backend.service.ServiceRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {
    @Mock AssistantProperties properties;
    @Mock VoiceInteractionRepository sessions;
    @Mock VoiceAssistantTurnRepository turns;
    @Mock AssistantCitationRepository citations;
    @Mock RobotRepository robots;
    @Mock AccountRepository accounts;
    @Mock EndpointRepository endpoints;
    @Mock ServiceRequestRepository requests;
    @Mock JobRepository jobs;
    @Mock KnowledgeDocumentRepository knowledgeDocuments;
    @Mock KnowledgeChunkRepository knowledgeChunks;
    @Mock HospitalInformationArticleRepository hospitalArticles;
    @Mock KnowledgeRetrievalService retrieval;
    @Mock OpenAiGateway openAi;
    @Mock ServiceRequestService requestService;
    @Mock EmergencyService emergencyService;
    @Spy ObjectMapper json = new ObjectMapper();
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks AssistantService service;

    @Test
    void idempotentReplayIssuesANewOneTimeConfirmationNonceWithoutCallingTheModel() {
        UUID sessionId = UUID.randomUUID();
        VoiceInteraction session = VoiceInteraction.builder()
                .voiceInteractionId(sessionId)
                .channel("KIOSK")
                .status(VoiceInteractionStatus.ACTIVE)
                .expiresAt(Instant.parse("2026-10-30T10:30:00Z"))
                .build();
        VoiceAssistantTurn prior = VoiceAssistantTurn.builder()
                .turnId(UUID.randomUUID())
                .interaction(session)
                .sequenceNo(1)
                .idempotencyKey("same-request")
                .status("AWAITING_CONFIRMATION")
                .createdAt(Instant.parse("2026-10-30T09:59:00Z"))
                .build();
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(turns.findByInteractionVoiceInteractionIdAndIdempotencyKey(sessionId, "same-request"))
                .thenReturn(Optional.of(prior));
        when(citations.findByTurnTurnIdOrderByOrdinalNoAsc(prior.getTurnId())).thenReturn(List.of());

        var response = service.textTurn(sessionId, "đưa tôi đến quầy thuốc", "same-request", "KIOSK", null);

        assertThat(response.turnId()).isEqualTo(prior.getTurnId());
        assertThat(response.confirmationNonce()).isNotBlank();
        assertThat(prior.getConfirmationNonceHash()).hasSize(64);
        assertThat(prior.getConfirmationExpiresAt()).isEqualTo(Instant.parse("2026-10-30T10:01:00Z"));
        verifyNoInteractions(openAi);
        verify(turns, never()).saveAndFlush(any());
    }
}
