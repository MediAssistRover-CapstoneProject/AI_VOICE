package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.voice.request.VoiceRequests.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ConflictException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.VoiceService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Phase12IntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accountRepository;
    @Autowired HospitalInformationRepository hospitalRepository;
    @Autowired FloorRepository floorRepository;
    @Autowired FacilityMapRepository mapRepository;
    @Autowired RobotRepository robotRepository;
    @Autowired VoiceInteractionRepository interactionRepository;
    @Autowired VoiceCommandRepository commandRepository;
    @Autowired VoiceService voiceService;
    @Autowired JwtTokenService jwtTokenService;

    private Account admin;
    private Account staff;
    private Robot robot;
    private DevicePrincipal device;

    @BeforeEach
    void setup() {
        clean();
        admin = account("P12-ADMIN", "phase12.admin", "phase12.admin@example.test", Role.ADMIN);
        staff = account("P12-STAFF", "phase12.staff", "phase12.staff@example.test", Role.STAFF);
        HospitalInformation hospital = hospitalRepository.save(HospitalInformation.builder().updatedBy(admin)
                .infoCategory("PROFILE").title("Hospital").content("Info").languageCode("vi-VN")
                .keywordsJson(objectMapper.createArrayNode()).status(HospitalInformationStatus.PUBLISHED)
                .publicVisible(true).build());
        Floor floor = floorRepository.save(Floor.builder().hospitalInformation(hospital).floorCode("P12-F1")
                .floorName("Floor 1").displayOrder(1).active(true).build());
        FacilityMap map = mapRepository.save(FacilityMap.builder().floor(floor).mapCode("P12-MAP").versionNo(1)
                .mapName("Phase 12 Map").widthM(new BigDecimal("50")).heightM(new BigDecimal("40"))
                .resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO)
                .originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map")
                .mapFileUri("https://example.test/maps/p12.yaml").assetHash("b".repeat(64))
                .status(MapStatus.ACTIVE).build());
        robot = robotRepository.save(Robot.builder().currentMap(map).robotCode("P12-ROBOT")
                .robotName("Phase 12 Robot").model("MVP").protocolVersion(1)
                .capabilitiesJson(objectMapper.createObjectNode()).status(RobotStatus.IDLE)
                .deviceTokenVersion(0).build());
        device = new DevicePrincipal(robot.getRobotId(), robot.getRobotCode(), 0,
                UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    @Test
    void recordsIdempotentInteractionAndCommandLifecycle() {
        UUID interactionId = UUID.randomUUID();
        var start = envelope(interactionId, "VOICE_INTERACTION_STARTED",
                new InteractionStartPayload("vi_VN", Instant.now().minusSeconds(30)));
        assertThat(voiceService.startInteraction(start, device).duplicate()).isFalse();
        assertThat(voiceService.startInteraction(start, device).duplicate()).isTrue();

        UUID commandId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(20);
        var command = envelope(commandId, "VOICE_COMMAND", new CommandPayload(interactionId, null, 1,
                "đưa tôi đến phòng khám", "đưa tôi đến phòng khám", "NAVIGATE", new BigDecimal("0.9500"),
                "NAVIGATION", objectMapper.createObjectNode().put("destination", "clinic"),
                VoiceCommandStatus.RECEIVED, receivedAt, null));
        assertThat(voiceService.recordCommand(command, device).duplicate()).isFalse();
        assertThat(voiceService.recordCommand(command, device).duplicate()).isTrue();

        var resolved = envelope(UUID.randomUUID(), "VOICE_COMMAND_RESULT",
                new CommandResultPayload(VoiceCommandStatus.RESOLVED, null, Instant.now().minusSeconds(10)));
        voiceService.recordCommandResult(commandId, resolved, device);
        var executed = envelope(UUID.randomUUID(), "VOICE_COMMAND_RESULT",
                new CommandResultPayload(VoiceCommandStatus.EXECUTED, null, Instant.now().minusSeconds(5)));
        voiceService.recordCommandResult(commandId, executed, device);

        var completed = envelope(UUID.randomUUID(), "VOICE_INTERACTION_COMPLETED",
                new InteractionCompletionPayload(VoiceInteractionStatus.COMPLETED, Instant.now(),
                        "NAVIGATE", "User requested navigation", "s3://voice/session.ogg"));
        voiceService.completeInteraction(interactionId, completed, device);

        assertThat(interactionRepository.findById(interactionId).orElseThrow().getStatus())
                .isEqualTo(VoiceInteractionStatus.COMPLETED);
        assertThat(commandRepository.findById(commandId).orElseThrow().getStatus())
                .isEqualTo(VoiceCommandStatus.EXECUTED);
        assertThat(voiceService.listInteractions(robot.getRobotId(), VoiceInteractionStatus.COMPLETED,
                null, null, AccountPrincipal.fromAccount(admin), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() " +
                "AND table_name='VoiceInteraction' AND column_name='voice_interaction_id'", String.class))
                .isEqualTo("binary");
    }

    @Test
    void rejectsInvalidSequenceTimingAndPrematureCompletion() {
        UUID interactionId = UUID.randomUUID();
        Instant startedAt = Instant.now().minusSeconds(30);
        voiceService.startInteraction(envelope(interactionId, "VOICE_INTERACTION_STARTED",
                new InteractionStartPayload("en-US", startedAt)), device);

        voiceService.recordCommand(envelope(UUID.randomUUID(), "VOICE_COMMAND",
                new CommandPayload(interactionId, null, 1, "help", null, "HELP", null,
                        "INFORMATION", objectMapper.createObjectNode(), VoiceCommandStatus.RECEIVED,
                        Instant.now().minusSeconds(20), null)), device);

        assertThatThrownBy(() -> voiceService.recordCommand(envelope(UUID.randomUUID(), "VOICE_COMMAND",
                new CommandPayload(interactionId, null, 1, "duplicate sequence", null, null, null,
                        "INFORMATION", objectMapper.createObjectNode(), VoiceCommandStatus.REJECTED,
                        Instant.now().minusSeconds(15), Instant.now().minusSeconds(10))), device))
                .isInstanceOf(ConflictException.class).hasMessageContaining("sequenceNo");

        assertThatThrownBy(() -> voiceService.completeInteraction(interactionId,
                envelope(UUID.randomUUID(), "VOICE_INTERACTION_COMPLETED",
                        new InteractionCompletionPayload(VoiceInteractionStatus.COMPLETED, Instant.now(),
                                null, null, null)), device))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("still RECEIVED");

        assertThatThrownBy(() -> voiceService.recordCommand(envelope(UUID.randomUUID(), "VOICE_COMMAND",
                new CommandPayload(interactionId, null, 2, "bad time", null, null, null,
                        "INFORMATION", objectMapper.createObjectNode(), VoiceCommandStatus.EXECUTED,
                        Instant.now(), Instant.now().minusSeconds(1))), device))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("processedAt");
    }

    @Test
    void allowsAuthorizedHistoryReadAndProtectsDeviceRoutes() throws Exception {
        UUID interactionId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        voiceService.startInteraction(envelope(interactionId, "VOICE_INTERACTION_STARTED",
                new InteractionStartPayload("vi-VN", Instant.now().minusSeconds(30))), device);
        voiceService.recordCommand(envelope(commandId, "VOICE_COMMAND",
                new CommandPayload(interactionId, null, 1, "secret raw text", "secret normalized text",
                        "INFORMATION", new BigDecimal("0.9900"), "INFORMATION",
                        objectMapper.createObjectNode().put("secret", "value"), VoiceCommandStatus.EXECUTED,
                        Instant.now().minusSeconds(20), Instant.now().minusSeconds(10))), device);
        voiceService.completeInteraction(interactionId, envelope(UUID.randomUUID(), "VOICE_INTERACTION_COMPLETED",
                new InteractionCompletionPayload(VoiceInteractionStatus.COMPLETED, Instant.now(),
                        "INFORMATION", "secret transcript", "s3://voice/secret.ogg")), device);

        String adminToken = jwtTokenService.issueAccessToken(admin).value();
        String staffToken = jwtTokenService.issueAccessToken(staff).value();

        mockMvc.perform(get("/api/v1/voice/interactions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transcriptSummary").doesNotExist())
                .andExpect(jsonPath("$.content[0].audioUri").doesNotExist());
        mockMvc.perform(get("/api/v1/voice/interactions")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rawDataAvailable").value(false));
        mockMvc.perform(get("/api/v1/voice/interactions/{id}", interactionId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptSummary").doesNotExist())
                .andExpect(jsonPath("$.audioUri").doesNotExist());
        mockMvc.perform(get("/api/v1/voice/interactions/{id}", interactionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptSummary").value("secret transcript"));
        mockMvc.perform(get("/api/v1/voice/commands")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rawText").doesNotExist())
                .andExpect(jsonPath("$.content[0].parameters").doesNotExist())
                .andExpect(jsonPath("$.content[0].rawDataAvailable").value(false));
        mockMvc.perform(get("/api/v1/voice/commands/{id}", commandId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/voice/commands/{id}", commandId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawText").value("secret raw text"));
        mockMvc.perform(post("/api/v1/pi/voice/interactions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(envelope(UUID.randomUUID(),
                                "VOICE_INTERACTION_STARTED",
                                new InteractionStartPayload("vi-VN", Instant.now())))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/voice/interactions/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    private <T> PiEnvelope<T> envelope(UUID messageId, String type, T payload) {
        return new PiEnvelope<>("1.0", messageId, "p12-" + messageId, UUID.randomUUID(),
                robot.getRobotId(), null, null, type, Instant.now(), UUID.randomUUID(), 1, payload);
    }

    private Account account(String code, String username, String email, Role role) {
        return accountRepository.save(Account.builder().accountCode(code).username(username).fullName(username)
                .email(email).role(role).authProvider(AuthProvider.LOCAL).passwordHash("unused")
                .status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
    }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"VoiceCommand", "VoiceInteraction", "Robot", "Map", "Floor",
                "HospitalInformation", "AuditLog", "RevokedToken", "RefreshToken", "StaffProfile", "Account"}) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
