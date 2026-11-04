package com.mediassistrover.backend.assistant;

import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KioskSpeechAccessTest {
    @Mock AssistantProperties properties;
    @Mock VoiceInteractionRepository sessions;
    @Mock VoiceAssistantTurnRepository turns;
    @Mock AssistantCitationRepository citations;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
    @InjectMocks AssistantService service;
    UUID sessionId = UUID.randomUUID();
    UUID turnId = UUID.randomUUID();
    VoiceInteraction session;
    VoiceAssistantTurn turn;

    @BeforeEach void setup() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isPublicEnabled()).thenReturn(true);
        session = VoiceInteraction.builder().voiceInteractionId(sessionId).channel("KIOSK")
                .status(VoiceInteractionStatus.ACTIVE).expiresAt(clock.instant().plusSeconds(300)).build();
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        turn = VoiceAssistantTurn.builder().turnId(turnId).interaction(session).status("READY")
                .groundingMode("HOSPITAL").answerText("Nhà thuốc ở tầng một.").build();
    }

    @Test void greetingIsFixedAndDoesNotLoadAnyTurn() {
        assertThat(service.kioskSpeechText(sessionId, null)).isEqualTo(KioskSpeechService.GREETING);
        verifyNoInteractions(turns, citations);
    }

    @Test void expiredSessionCannotObtainEvenGreeting() {
        session.setExpiresAt(clock.instant().minusSeconds(1));
        assertThatThrownBy(() -> service.kioskSpeechText(sessionId, null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void anotherChannelCannotUseKioskSpeech() {
        session.setChannel("PI");
        assertThatThrownBy(() -> service.kioskSpeechText(sessionId, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void turnMustBelongToRequestedSession() {
        turn.setInteraction(VoiceInteraction.builder().voiceInteractionId(UUID.randomUUID()).channel("KIOSK").build());
        when(turns.findById(turnId)).thenReturn(Optional.of(turn));
        assertThatThrownBy(() -> service.kioskSpeechText(sessionId, turnId)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(citations);
    }

    @Test void failedTurnCannotBeSynthesized() {
        turn.setStatus("FAILED");
        when(turns.findById(turnId)).thenReturn(Optional.of(turn));
        assertThatThrownBy(() -> service.kioskSpeechText(sessionId, turnId)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void missingActualCitationsProducesFixedRefusal() {
        when(turns.findById(turnId)).thenReturn(Optional.of(turn));
        when(citations.findByTurnTurnIdOrderByOrdinalNoAsc(turnId)).thenReturn(List.of());
        assertThat(service.kioskSpeechText(sessionId, turnId)).isEqualTo(KioskSpeechService.OUT_OF_SCOPE);
    }

    @Test void groundedAnswerUsesStoredText() {
        when(turns.findById(turnId)).thenReturn(Optional.of(turn));
        when(citations.findByTurnTurnIdOrderByOrdinalNoAsc(turnId)).thenReturn(List.of(new AssistantCitation()));
        assertThat(service.kioskSpeechText(sessionId, turnId)).isEqualTo(turn.getAnswerText());
    }

    @Test void disabledPublicAssistantCannotProvideSpeech() {
        when(properties.isPublicEnabled()).thenReturn(false);
        // Disabled check occurs before any session lookup.
        clearInvocations(sessions);
        lenient().when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> service.kioskSpeechText(sessionId, null)).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(sessions, turns, citations);
    }
}
