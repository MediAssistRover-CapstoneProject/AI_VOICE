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
    @Mock VoiceServiceGateway pythonVoice;
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

    @Test
    void missingHospitalEvidenceRefusesWithoutCallingAnswerModelOrKioskTts() {
        UUID id=UUID.randomUUID();
        VoiceInteraction session=VoiceInteraction.builder().voiceInteractionId(id).channel("KIOSK")
            .languageCode("vi-VN").visibilityScope("PUBLIC").status(VoiceInteractionStatus.ACTIVE)
            .expiresAt(Instant.parse("2026-10-30T10:30:00Z")).build();
        when(sessions.findById(id)).thenReturn(Optional.of(session));
        when(turns.saveAndFlush(any())).thenAnswer(call -> {
            VoiceAssistantTurn turn=call.getArgument(0);turn.setTurnId(UUID.randomUUID());return turn;
        });
        when(properties.sessionTtl()).thenReturn(java.time.Duration.ofMinutes(30));
        var response=service.textTurn(id,"Giá cổ phiếu hôm nay?","no-source","KIOSK",null);
        assertThat(response.answer()).isEqualTo("Câu hỏi này nằm ngoài phạm vi hỗ trợ của tôi. Tôi chỉ hỗ trợ thông tin có trong tài liệu bệnh viện.");
        assertThat(response.status()).isEqualTo("READY");
        verifyNoInteractions(openAi);
    }

    private UUID setupTurn() {
        UUID id=UUID.randomUUID();
        VoiceInteraction session=VoiceInteraction.builder().voiceInteractionId(id).channel("KIOSK")
            .languageCode("vi-VN").visibilityScope("PUBLIC").status(VoiceInteractionStatus.ACTIVE)
            .expiresAt(Instant.parse("2026-10-30T10:30:00Z")).build();
        when(sessions.findById(id)).thenReturn(Optional.of(session));
        when(turns.saveAndFlush(any())).thenAnswer(call->{VoiceAssistantTurn t=call.getArgument(0);t.setTurnId(UUID.randomUUID());return t;});
        when(properties.sessionTtl()).thenReturn(java.time.Duration.ofMinutes(30));
        return id;
    }

    @Test
    void reviewedPythonFaqSkipsJavaRetrievalAndModel() {
        UUID id=setupTurn();
        var source=new KnowledgeRetrievalService.Source("VOICE1","DOCUMENT",null,null,null,"FAQ test","local:fixture","Test answer","hash",1,null);
        var result=json.createObjectNode().put("intent","INFORMATION").put("answer","Test answer")
            .put("speech_text","Test answer").put("grounding_mode","HOSPITAL");
        result.putArray("used_source_ids").add("VOICE1");result.putArray("warnings");
        when(pythonVoice.answer(eq("Test FAQ?"),anyList(),eq(true),eq(false)))
            .thenReturn(Optional.of(new VoiceServiceGateway.Answer(result,List.of(source),null)));
        var response=service.textTurn(id,"Test FAQ?","python-faq","KIOSK",null);
        assertThat(response.answer()).isEqualTo("Test answer");
        assertThat(response.model()).isNull();
        assertThat(response.status()).isEqualTo("READY");
        verifyNoInteractions(retrieval,openAi,requestService,emergencyService);
    }

    private KnowledgeRetrievalService.Source hospitalSource() {
        return new KnowledgeRetrievalService.Source("S1","DOCUMENT",UUID.randomUUID(),null,UUID.randomUUID(),
            "Sổ tay bệnh viện","Trang 1","Nhà thuốc ở tầng một.","hash",0.9,null);
    }

    @Test
    void oldGeneralKnowledgeSettingCannotEnableOutOfScopeAnswers() {
        UUID id=setupTurn();
        lenient().when(properties.isGeneralKnowledgeEnabled()).thenReturn(true);
        var response=service.textTurn(id,"Albert Einstein là ai?","general","KIOSK",null);
        assertThat(response.answer()).contains("ngoài phạm vi hỗ trợ");
        assertThat(response.warnings()).contains("OUTSIDE_HOSPITAL_SCOPE");
        verifyNoInteractions(openAi);
    }

    @Test
    void unrelatedSourcesDoNotPermitGeneralKnowledge() throws Exception {
        UUID id=setupTurn();
        when(retrieval.retrieve(anyString(),anyString(),anyBoolean())).thenReturn(List.of(hospitalSource()));
        when(openAi.answer(anyString(),anyString(),any())).thenReturn(new ObjectMapper().readTree("""
          {"intent":"INFORMATION","answer":"Einstein là nhà vật lý.","speech_text":"Einstein là nhà vật lý.",
           "language":"en-US","grounding_mode":"GENERAL","used_source_ids":[]}
          """));
        var response=service.textTurn(id,"Einstein là ai?","unrelated","KIOSK",null);
        assertThat(response.answer()).contains("ngoài phạm vi hỗ trợ");
        assertThat(response.speechText()).isEqualTo(response.answer());
        assertThat(response.language()).isEqualTo("vi-VN");
        verify(citations,never()).save(any());
    }

    @Test
    void inventedCitationCannotAuthorizeHospitalAnswerOrAction() throws Exception {
        UUID id=setupTurn();
        when(retrieval.retrieve(anyString(),anyString(),anyBoolean())).thenReturn(List.of(hospitalSource()));
        when(openAi.answer(anyString(),anyString(),any())).thenReturn(new ObjectMapper().readTree("""
          {"intent":"ESCORT","answer":"Đi theo tôi.","grounding_mode":"HOSPITAL","used_source_ids":["FAKE"]}
          """));
        var response=service.textTurn(id,"Dẫn đường","fake-citation","KIOSK",null);
        assertThat(response.answer()).contains("ngoài phạm vi hỗ trợ");
        assertThat(response.actionPreview()).isNull();
        verifyNoInteractions(requestService,emergencyService);
    }

    @Test
    void supportedAnswerKeepsItsRealSource() throws Exception {
        UUID id=setupTurn();
        when(retrieval.retrieve(anyString(),anyString(),anyBoolean())).thenReturn(List.of(hospitalSource()));
        when(openAi.answer(anyString(),anyString(),any())).thenReturn(new ObjectMapper().readTree("""
          {"intent":"INFORMATION","answer":"Nhà thuốc ở tầng một.","speech_text":"Nhà thuốc ở tầng một.",
           "language":"vi-VN","grounding_mode":"HOSPITAL","used_source_ids":["S1"]}
          """));
        var response=service.textTurn(id,"Nhà thuốc ở đâu?","supported","KIOSK",null);
        assertThat(response.answer()).isEqualTo("Nhà thuốc ở tầng một.");
        assertThat(response.groundingMode()).isEqualTo("HOSPITAL");
        verify(citations).save(any());
        verify(openAi,never()).synthesizeWav(anyString(),anyString());
    }
}
