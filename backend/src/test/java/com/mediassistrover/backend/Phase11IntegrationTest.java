package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.monitoring.request.MonitoringRequests.*;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ConflictException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.MonitoringService;
import com.mediassistrover.backend.service.RequestMetadata;
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

class Phase11IntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accountRepository;
    @Autowired HospitalInformationRepository hospitalRepository;
    @Autowired FloorRepository floorRepository;
    @Autowired FacilityMapRepository mapRepository;
    @Autowired ZoneRepository zoneRepository;
    @Autowired EndpointRepository endpointRepository;
    @Autowired RobotRepository robotRepository;
    @Autowired DetectionEventRepository detectionRepository;
    @Autowired CrowdDensityRecordRepository crowdRepository;
    @Autowired HeatmapRecordRepository heatmapRepository;
    @Autowired MonitoringService monitoringService;
    @Autowired JwtTokenService jwtTokenService;

    private Account admin;
    private Account staff;
    private FacilityMap map;
    private Zone zone;
    private Endpoint checkpoint;
    private Robot robot;
    private DevicePrincipal device;

    @BeforeEach
    void setup() {
        clean();
        admin = account("P11-ADMIN", "phase11.admin", "phase11.admin@example.test", Role.ADMIN);
        staff = account("P11-STAFF", "phase11.staff", "phase11.staff@example.test", Role.STAFF);
        HospitalInformation hospital = hospitalRepository.save(HospitalInformation.builder().updatedBy(admin)
                .infoCategory("PROFILE").title("Hospital").content("Info").languageCode("vi-VN")
                .keywordsJson(objectMapper.createArrayNode()).status(HospitalInformationStatus.PUBLISHED)
                .publicVisible(true).build());
        Floor floor = floorRepository.save(Floor.builder().hospitalInformation(hospital).floorCode("P11-F1")
                .floorName("Floor 1").displayOrder(1).active(true).build());
        map = mapRepository.save(FacilityMap.builder().floor(floor).mapCode("P11-MAP").versionNo(1)
                .mapName("Phase 11 Map").widthM(new BigDecimal("50")).heightM(new BigDecimal("40"))
                .resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO)
                .originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map")
                .mapFileUri("https://example.test/maps/p11.yaml").assetHash("a".repeat(64))
                .status(MapStatus.ACTIVE).build());
        zone = zoneRepository.save(Zone.builder().map(map).zoneCode("P11-ZONE").zoneName("Waiting area")
                .zoneType(ZoneType.NO_STOP).polygonJson(objectMapper.createArrayNode()).active(true)
                .publicVisible(true).build());
        checkpoint = endpointRepository.save(Endpoint.builder().map(map).zone(zone).endpointCode("P11-CP")
                .endpointName("Checkpoint").endpointType(EndpointType.WAYPOINT).xM(BigDecimal.ONE)
                .yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true)
                .publicVisible(true).build());
        robot = robotRepository.save(Robot.builder().currentMap(map).robotCode("P11-ROBOT")
                .robotName("Phase 11 Robot").model("MVP").protocolVersion(1)
                .capabilitiesJson(objectMapper.createObjectNode()).status(RobotStatus.IDLE)
                .deviceTokenVersion(0).build());
        device = new DevicePrincipal(robot.getRobotId(), robot.getRobotCode(), 0, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(3600));
    }

    @AfterEach
    void cleanup() { clean(); }

    @Test
    void ingestsDetectionIdempotentlyAndValidatesLocation() {
        UUID messageId = UUID.randomUUID();
        DetectionPayload payload = new DetectionPayload(map.getMapId(), zone.getZoneId(),
                DetectionEventType.SUSPECTED_FALL, new BigDecimal("0.9250"), new BigDecimal("2.5"),
                new BigDecimal("3.5"), DetectionLocationBasis.OBJECT, "vision-1",
                objectMapper.createObjectNode().put("source", "camera"), "s3://evidence/event.jpg",
                Instant.parse("2026-10-06T10:00:00Z"));
        PiEnvelope<DetectionPayload> envelope = envelope(messageId, "DETECTION_EVENT", payload);

        assertThat(monitoringService.recordDetection(envelope, device).duplicate()).isFalse();
        assertThat(monitoringService.recordDetection(envelope, device).duplicate()).isTrue();
        assertThat(detectionRepository.count()).isEqualTo(1);
        assertThat(monitoringService.listDetections(robot.getRobotId(), DetectionEventType.SUSPECTED_FALL,
                null, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() " +
                "AND table_name='DetectionEvent' AND column_name='detection_event_id'", String.class))
                .isEqualTo("binary");

        DetectionPayload invalid = new DetectionPayload(map.getMapId(), zone.getZoneId(),
                DetectionEventType.OTHER, null, BigDecimal.ONE, null, DetectionLocationBasis.ROBOT,
                null, objectMapper.createObjectNode(), null, Instant.now());
        assertThatThrownBy(() -> monitoringService.recordDetection(
                envelope(UUID.randomUUID(), "DETECTION_EVENT", invalid), device))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("both be present");
    }

    @Test
    void ingestsCrowdDensityIdempotentlyAndRejectsHeatmapWithoutPersonPositions() throws Exception {
        UUID messageId = UUID.randomUUID();
        CrowdDensityPayload payload = new CrowdDensityPayload(zone.getZoneId(), checkpoint.getEndpointId(),
                12, CrowdDensityLevel.MEDIUM, new BigDecimal("0.8700"), "crowd-1",
                objectMapper.createObjectNode().put("samplingSeconds", 10), null,
                Instant.parse("2026-10-06T10:05:00Z"));
        PiEnvelope<CrowdDensityPayload> envelope = envelope(messageId, "CROWD_DENSITY_RECORD", payload);

        assertThat(monitoringService.recordCrowdDensity(envelope, device).duplicate()).isFalse();
        assertThat(monitoringService.recordCrowdDensity(envelope, device).duplicate()).isTrue();
        assertThat(crowdRepository.count()).isEqualTo(1);

        HeatmapCreate request = heatmapRequest();
        assertThatThrownBy(() -> monitoringService.createHeatmap(request, admin.getAccountId(), metadata()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("person positions");
        assertThat(heatmapRepository.count()).isZero();

        String adminToken = jwtTokenService.issueAccessToken(admin).value();
        mockMvc.perform(delete("/api/v1/admin/monitoring/heatmaps/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void protectsAdminMonitoringEndpointsFromStaff() throws Exception {
        String adminToken = jwtTokenService.issueAccessToken(admin).value();
        String staffToken = jwtTokenService.issueAccessToken(staff).value();

        mockMvc.perform(get("/api/v1/admin/monitoring/detections")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/monitoring/detections")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/pi/monitoring/detections")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(
                                envelope(UUID.randomUUID(), "DETECTION_EVENT",
                                        new DetectionPayload(map.getMapId(), zone.getZoneId(),
                                                DetectionEventType.OTHER, null, null, null,
                                                DetectionLocationBasis.UNKNOWN, null,
                                                objectMapper.createObjectNode(), null, Instant.now())))))
                .andExpect(status().isForbidden());
    }

    private HeatmapCreate heatmapRequest() {
        return new HeatmapCreate(map.getMapId(), "crowd_density",
                Instant.parse("2026-10-06T09:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"),
                new BigDecimal("1.0000"), objectMapper.createObjectNode().set("cells",
                        objectMapper.createArrayNode().add(12)),
                "s3://heatmaps/p11.png", new BigDecimal("12.0000"), 1);
    }

    private <T> PiEnvelope<T> envelope(UUID messageId, String type, T payload) {
        return new PiEnvelope<>("1.0", messageId, "p11-" + messageId, UUID.randomUUID(),
                robot.getRobotId(), null, null, type, Instant.now(), UUID.randomUUID(), 1, payload);
    }

    private Account account(String code, String username, String email, Role role) {
        return accountRepository.save(Account.builder().accountCode(code).username(username).fullName(username)
                .email(email).role(role).authProvider(AuthProvider.LOCAL).passwordHash("unused")
                .status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
    }

    private RequestMetadata metadata() { return new RequestMetadata("127.0.0.1", "test", "phase11-test"); }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"MonitoringIngestionReceipt","HeatmapRecord", "CrowdDensityRecord", "DetectionEvent", "Robot",
                "Endpoint", "Zone", "Map", "Floor", "HospitalInformation", "AuditLog", "RevokedToken",
                "RefreshToken", "StaffProfile", "Account"}) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
