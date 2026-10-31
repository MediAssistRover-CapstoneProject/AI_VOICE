package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.RobotService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RobotPiIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired HospitalInformationRepository hospitalRepository;
    @Autowired FloorRepository floorRepository;
    @Autowired FacilityMapRepository mapRepository;
    @Autowired RobotRepository robotRepository;
    @Autowired RobotStatusHistoryRepository statusRepository;
    @Autowired RobotLocationHistoryRepository locationRepository;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired RobotService robotService;

    private Account admin;
    private String adminToken;
    private FacilityMap map;

    @BeforeEach void setup(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(String t:new String[]{"RobotLocationHistory","RobotStatusHistory","Robot","EndpointGroupMember","Endpoint","Room","EndpointGroup","Zone","Map","Floor","HospitalInformation","AuditLog","RevokedToken","RefreshToken","StaffProfile","Account"})jdbc.execute("TRUNCATE TABLE `"+t+"`");jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        admin=accountRepository.save(Account.builder().accountCode("ADM-PI").username("pi.admin").fullName("PI Admin").email("pi.admin@example.test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());adminToken=jwtTokenService.issueAccessToken(admin).value();
        HospitalInformation h=hospitalRepository.save(HospitalInformation.builder().updatedBy(admin).infoCategory("HOSPITAL_PROFILE").title("Hospital").content("Profile").languageCode("vi-VN").keywordsJson(objectMapper.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());
        Floor f=floorRepository.save(Floor.builder().hospitalInformation(h).floorCode("F1").floorName("Floor 1").displayOrder(1).active(true).build());
        map=mapRepository.save(FacilityMap.builder().floor(f).mapCode("MAP-PI").versionNo(1).mapName("PI Map").widthM(BigDecimal.TEN).heightM(BigDecimal.TEN).resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map").mapFileUri("https://assets.example/map.yaml").assetHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").status(MapStatus.ACTIVE).build());
    }

    @Test void provisionAuthenticateHeartbeatLocationAndDedupe() throws Exception {
        UUID robotId=createRobot("ROBOT-01");String secret=provision(robotId);Robot stored=robotRepository.findById(robotId).orElseThrow();assertThat(stored.getDeviceSecretHash()).isNotEqualTo(secret).startsWith("$2");
        String deviceToken=deviceToken("ROBOT-01",secret);
        UUID heartbeatId=UUID.randomUUID();String starting=heartbeat(robotId,heartbeatId,"STARTING");
        mockMvc.perform(post("/api/v1/pi/heartbeat").header("Authorization",bearer(deviceToken)).contentType(MediaType.APPLICATION_JSON).content(starting)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false)).andExpect(jsonPath("$.robotStatus").value("STARTING")).andExpect(jsonPath("$.mapSyncRequired").value(true));
        mockMvc.perform(post("/api/v1/pi/heartbeat").header("Authorization",bearer(deviceToken)).contentType(MediaType.APPLICATION_JSON).content(starting)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mockMvc.perform(post("/api/v1/pi/heartbeat").header("Authorization",bearer(deviceToken)).contentType(MediaType.APPLICATION_JSON).content(heartbeat(robotId,UUID.randomUUID(),"IDLE"))).andExpect(status().isOk()).andExpect(jsonPath("$.robotStatus").value("IDLE"));

        UUID locationId=UUID.randomUUID();String location=location(robotId,locationId);
        mockMvc.perform(post("/api/v1/pi/telemetry/location").header("Authorization",bearer(deviceToken)).contentType(MediaType.APPLICATION_JSON).content(location)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false));
        mockMvc.perform(post("/api/v1/pi/telemetry/location").header("Authorization",bearer(deviceToken)).contentType(MediaType.APPLICATION_JSON).content(location)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        assertThat(locationRepository.count()).isEqualTo(1);assertThat(statusRepository.findById(heartbeatId)).isPresent();
    }

    @Test void deviceTokenIsRobotScopedAndRotationInvalidatesOldToken() throws Exception {
        UUID robotId=createRobot("ROBOT-02");String secret=provision(robotId);String oldToken=deviceToken("ROBOT-02",secret);
        mockMvc.perform(post("/api/v1/pi/heartbeat").header("Authorization",bearer(oldToken)).contentType(MediaType.APPLICATION_JSON).content(heartbeat(UUID.randomUUID(),UUID.randomUUID(),"STARTING"))).andExpect(status().isUnprocessableEntity());
        JsonNode rotated=response(post("/api/v1/robots/{id}/rotate-secret",robotId).header("Authorization",bearer(adminToken)),200);
        assertThat(rotated.get("deviceSecret").asText()).isNotEqualTo(secret);
        mockMvc.perform(get("/api/v1/pi/catalog/manifest").header("Authorization",bearer(oldToken))).andExpect(status().isUnauthorized());
        String newToken=deviceToken("ROBOT-02",rotated.get("deviceSecret").asText());
        mockMvc.perform(get("/api/v1/pi/catalog/manifest").header("Authorization",bearer(newToken))).andExpect(status().isOk()).andExpect(jsonPath("$.robotId").value(robotId.toString()));
    }

    @Test void heartbeatTimeoutMarksRobotOfflineAndRecordsTransition() throws Exception {
        UUID robotId=createRobot("ROBOT-03");Robot robot=robotRepository.findById(robotId).orElseThrow();robot.setStatus(RobotStatus.IDLE);robot.setLastHeartbeatAt(Instant.now().minusSeconds(31));robotRepository.saveAndFlush(robot);
        assertThat(robotService.markHeartbeatTimeoutsOffline()).isEqualTo(1);
        assertThat(robotRepository.findById(robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.OFFLINE);
        assertThat(statusRepository.findByRobotRobotId(robotId,org.springframework.data.domain.Pageable.unpaged()).getContent()).anyMatch(h->"HEARTBEAT_TIMEOUT".equals(h.getReason()));
    }

    @Test void maintenanceAndReconcileFollowRobotStateMachine() throws Exception {
        UUID robotId=createRobot("ROBOT-04");
        String reason="{\"reason\":\"Planned inspection\"}";
        mockMvc.perform(post("/api/v1/robots/{id}/maintenance",robotId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isUnprocessableEntity());
        Robot robot=robotRepository.findById(robotId).orElseThrow();robot.setStatus(RobotStatus.IDLE);robotRepository.saveAndFlush(robot);
        mockMvc.perform(post("/api/v1/robots/{id}/maintenance",robotId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MAINTENANCE"));
        mockMvc.perform(post("/api/v1/robots/{id}/reconcile",robotId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Inspection completed\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STARTING"));
    }

    private UUID createRobot(String code)throws Exception{String body="""
            {"robotCode":"%s","robotName":"Robot","model":"MentorPi M1","currentMapId":"%s",
             "protocolVersion":1,"capabilitiesJson":{"move":true}}
            """.formatted(code,map.getMapId());return UUID.fromString(response(post("/api/v1/robots").header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(body),201).get("robotId").asText());}
    private String provision(UUID id)throws Exception{return response(post("/api/v1/robots/{id}/provision-secret",id).header("Authorization",bearer(adminToken)),200).get("deviceSecret").asText();}
    private String deviceToken(String code,String secret)throws Exception{return response(post("/api/v1/pi/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"robotCode\":\"%s\",\"deviceSecret\":\"%s\"}".formatted(code,secret)),200).get("accessToken").asText();}
    private String heartbeat(UUID robotId,UUID messageId,String status){return """
            {"protocolVersion":"1.0","messageId":"%s","operationKey":"hb-%s","correlationId":"%s",
             "robotId":"%s","messageType":"HEARTBEAT","occurredAt":"%s","producerSessionId":"%s","sequenceNo":1,
             "payload":{"status":"%s","runtimeVersion":"1.0.0","firmwareVersion":"1.0.0","protocolMajor":1,
             "capabilities":{"move":true},"deployedCatalogHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "mapAssetHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","currentMapId":"%s","batteryRaw":1200,"batteryUnit":"RAW_ADC","batteryCapturedAt":"%s"}}
            """.formatted(messageId,messageId,UUID.randomUUID(),robotId,Instant.now(),UUID.randomUUID(),status,map.getMapId(),Instant.now());}
    private String location(UUID robotId,UUID messageId){return """
            {"protocolVersion":"1.0","messageId":"%s","operationKey":"loc-%s","correlationId":"%s",
             "robotId":"%s","messageType":"ROBOT_LOCATION","occurredAt":"%s","producerSessionId":"%s","sequenceNo":2,
             "payload":{"mapId":"%s","xM":1.2,"yM":2.3,"yawRad":0.2,"localizationConfidence":0.95,"fresh":true,"capturedAt":"%s"}}
            """.formatted(messageId,messageId,UUID.randomUUID(),robotId,Instant.now(),UUID.randomUUID(),map.getMapId(),Instant.now());}
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,int expected)throws Exception{MvcResult r=mockMvc.perform(req).andExpect(status().is(expected)).andReturn();return objectMapper.readTree(r.getResponse().getContentAsString());}
    private String bearer(String token){return "Bearer "+token;}
}
