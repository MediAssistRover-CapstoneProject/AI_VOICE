package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.AccountRepository;
import com.mediassistrover.backend.repository.FacilityMapRepository;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.FacilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FacilityIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired FacilityMapRepository mapRepository;
    @Autowired FacilityService facilityService;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired JdbcTemplate jdbcTemplate;

    private Account admin;
    private String adminToken;

    @BeforeEach
    void cleanAndCreateAdmin() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"RobotLocationHistory","RobotStatusHistory","Robot","EndpointGroupMember","Endpoint","Room","EndpointGroup","Zone","Map","Floor","HospitalInformationArticle","HospitalInformation","AuditLog","RevokedToken","RefreshToken","StaffProfile","Account"}) {
            jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
        admin = accountRepository.save(activeAccount("ADM-FACILITY", "facility.admin", Role.ADMIN));
        adminToken = jwtTokenService.issueAccessToken(admin).value();
    }

    @Test
    void facilityCatalogSupportsPublicFilteringAndRelationshipValidation() throws Exception {
        UUID hospitalId = createHospitalProfile(true, true);
        UUID floorId = createFloor(hospitalId);
        UUID mapId = createMap(floorId, "MAP-F1", 1);

        String publicZone = """
                {"mapId":"%s","zoneCode":"PUBLIC","zoneName":"Public Zone","zoneType":"NO_STOP",
                 "polygonJson":{"points":[[0,0],[1,0],[1,1]]},"active":true,"publicVisible":true}
                """.formatted(mapId);
        mockMvc.perform(post("/api/v1/zones").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(publicZone))
                .andExpect(status().isCreated());

        String privateZone = publicZone.replace("PUBLIC", "PRIVATE").replace("Public Zone", "Private Zone")
                .replace("\"publicVisible\":true", "\"publicVisible\":false");
        mockMvc.perform(post("/api/v1/zones").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(privateZone))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/public/hospital-information"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].updatedByAccountId").doesNotExist())
                .andExpect(jsonPath("$.content[0].status").doesNotExist());
        mockMvc.perform(get("/api/v1/public/zones"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].zoneCode").value("PUBLIC"))
                .andExpect(jsonPath("$.content[0].polygonJson").doesNotExist())
                .andExpect(jsonPath("$.content[0].cameraSourceRef").doesNotExist());

        UUID secondMapId = createMap(floorId, "MAP-F1", 2);
        UUID groupId = createGroup(mapId);
        UUID endpointId = createEndpoint(secondMapId);
        mockMvc.perform(post("/api/v1/endpoint-group-members").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpointGroupId\":\"%s\",\"endpointId\":\"%s\",\"sequenceNo\":1,\"active\":true}"
                                .formatted(groupId, endpointId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));

        UUID publicEndpointId = createEndpoint(mapId);
        mockMvc.perform(post("/api/v1/endpoint-group-members").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpointGroupId\":\"%s\",\"endpointId\":\"%s\",\"sequenceNo\":1,\"active\":true}"
                                .formatted(groupId, publicEndpointId)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/public/endpoints").param("mapId", mapId.toString())
                        .param("endpointType", "WAYPOINT").param("accessible", "true").param("q", "Endpoint"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].xM").doesNotExist())
                .andExpect(jsonPath("$.content[0].description").doesNotExist());
        mockMvc.perform(get("/api/v1/public/endpoint-groups/{id}/members", groupId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].endpointId").value(publicEndpointId.toString()));
        mockMvc.perform(get("/api/v1/public/endpoint-groups/{id}", groupId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectionPolicy").doesNotExist())
                .andExpect(jsonPath("$.allowFallback").doesNotExist());

        UUID articleId = createAndPublishArticle(hospitalId);
        mockMvc.perform(get("/api/v1/public/hospital-information/{hospitalId}/articles", hospitalId)
                        .param("q", "pharmacy").param("category", "PROCESS").param("languageCode", "vi-VN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].updatedByAccountId").doesNotExist())
                .andExpect(jsonPath("$.content[0].rowVersion").doesNotExist());
        mockMvc.perform(get("/api/v1/public/hospital-information/{hospitalId}/articles/{articleId}", hospitalId, articleId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("Go to pharmacy counter"));

        UUID privateHospital = createHospitalProfile(false, true);
        mockMvc.perform(get("/api/v1/public/hospital-information/{id}", privateHospital))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentActivationLeavesExactlyOneActiveMapPerFloor() throws Exception {
        UUID hospitalId = createHospitalProfile(true, true);
        UUID floorId = createFloor(hospitalId);
        UUID first = createMap(floorId, "CONCURRENT", 1);
        UUID second = createMap(floorId, "CONCURRENT", 2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var f1 = executor.submit(() -> { start.await(); return facilityService.activateMap(first, admin.getAccountId(), null); });
            var f2 = executor.submit(() -> { start.await(); return facilityService.activateMap(second, admin.getAccountId(), null); });
            start.countDown();
            f1.get(); f2.get();
        }

        assertThat(mapRepository.findAllByFloorFloorIdAndStatus(floorId, MapStatus.ACTIVE)).hasSize(1);
        assertThat(mapRepository.findAllByFloorFloorIdAndStatus(floorId, MapStatus.INACTIVE)).hasSize(1);
    }

    @Test
    void staffCanReadButCannotMutateFacilityCatalog() throws Exception {
        UUID hospitalId = createHospitalProfile(true, true);
        Account staff = accountRepository.save(activeAccount("STF-FACILITY", "facility.staff", Role.STAFF));
        String staffToken = jwtTokenService.issueAccessToken(staff).value();
        mockMvc.perform(get("/api/v1/hospital-information").header("Authorization", bearer(staffToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/floors").header("Authorization", bearer(staffToken))
                        .contentType(MediaType.APPLICATION_JSON).content(floorJson(hospitalId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void rejectsLegacyAndUnknownEndpointAndZoneTypes() throws Exception {
        UUID hospitalId = createHospitalProfile(true, false);
        UUID floorId = createFloor(hospitalId);
        UUID mapId = createMap(floorId, "TYPE-VALIDATION", 1);

        String endpoint = "{\"mapId\":\"%s\",\"endpointCode\":\"INVALID\",\"endpointName\":\"Invalid\",\"endpointType\":\"PICKUP\",\"xM\":1,\"yM\":2,\"yawRad\":0}".formatted(mapId);
        mockMvc.perform(post("/api/v1/endpoints").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(endpoint))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        String zone = "{\"mapId\":\"%s\",\"zoneCode\":\"INVALID\",\"zoneName\":\"Invalid\",\"zoneType\":\"RESTRICTED\",\"polygonJson\":{\"points\":[[0,0],[1,0],[1,1]]}}".formatted(mapId);
        mockMvc.perform(post("/api/v1/zones").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(zone))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/public/endpoints").param("endpointType", "PICKUP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("endpointType"));
        mockMvc.perform(get("/api/v1/public/zones").param("zoneType", "RESTRICTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("zoneType"));
    }

    private UUID createHospitalProfile(boolean publicVisible, boolean publish) throws Exception {
        String body = """
                {"infoCategory":"HOSPITAL_PROFILE","title":"MediAssist Hospital","content":"Hospital profile",
                 "languageCode":"vi-VN","keywordsJson":["hospital"],"publicVisible":%s}
                """.formatted(publicVisible);
        JsonNode created = response(post("/api/v1/hospital-information").header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(body), 201);
        UUID id = UUID.fromString(created.get("hospitalInformationId").asText());
        if (publish) response(post("/api/v1/hospital-information/{id}/publish", id).header("Authorization", bearer(adminToken)), 200);
        return id;
    }

    private UUID createFloor(UUID hospitalId) throws Exception {
        return UUID.fromString(response(post("/api/v1/floors").header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(floorJson(hospitalId)), 201).get("floorId").asText());
    }

    private String floorJson(UUID hospitalId) {
        return "{\"hospitalInformationId\":\"%s\",\"floorCode\":\"F1\",\"floorName\":\"Floor 1\",\"displayOrder\":1,\"active\":true}".formatted(hospitalId);
    }

    private UUID createMap(UUID floorId, String code, int version) throws Exception {
        String body = """
                {"floorId":"%s","mapCode":"%s","versionNo":%d,"mapName":"Map %d",
                 "widthM":100,"heightM":80,"resolutionMPerPixel":0.05,"originXM":0,"originYM":0,
                 "originYawRad":0,"frameId":"map","mapFileUri":"https://assets.example/maps/%s/%d",
                 "assetHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """.formatted(floorId, code, version, version, code, version);
        return UUID.fromString(response(post("/api/v1/maps").header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(body), 201).get("mapId").asText());
    }

    private UUID createGroup(UUID mapId) throws Exception {
        String body="{\"mapId\":\"%s\",\"groupCode\":\"DEST\",\"groupName\":\"Destination\",\"selectionPolicy\":\"PRIORITY\",\"allowFallback\":true,\"active\":true}".formatted(mapId);
        return UUID.fromString(response(post("/api/v1/endpoint-groups").header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(body),201).get("endpointGroupId").asText());
    }

    private UUID createEndpoint(UUID mapId) throws Exception {
        String body="{\"mapId\":\"%s\",\"endpointCode\":\"EP-1\",\"endpointName\":\"Endpoint 1\",\"endpointType\":\"WAYPOINT\",\"xM\":1,\"yM\":2,\"yawRad\":0,\"active\":true,\"accessible\":true,\"publicVisible\":true}".formatted(mapId);
        return UUID.fromString(response(post("/api/v1/endpoints").header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(body),201).get("endpointId").asText());
    }

    private UUID createAndPublishArticle(UUID hospitalId) throws Exception {
        String body = """
                {"articleCode":"PHARMACY_GUIDE","category":"PROCESS","canonicalQuestion":"Where is pharmacy?",
                 "title":"Pharmacy guide","answer":"Go to pharmacy counter","languageCode":"vi-VN",
                 "keywordsJson":["pharmacy"],"publicVisible":true}
                """;
        JsonNode created = response(post("/api/v1/hospital-information/{hospitalId}/articles", hospitalId)
                .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(body), 201);
        UUID id = UUID.fromString(created.get("articleId").asText());
        response(post("/api/v1/hospital-information/{hospitalId}/articles/{articleId}/publish", hospitalId, id)
                .header("Authorization", bearer(adminToken)), 200);
        return id;
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status) throws Exception {
        var result=mockMvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Account activeAccount(String code,String username,Role role){Instant now=Instant.now();return Account.builder().accountCode(code).username(username).fullName(username).email(username+"@example.test").role(role).authProvider(AuthProvider.LOCAL).passwordHash("not-used").status(AccountStatus.ACTIVE).passwordChangedAt(now).build();}
    private String bearer(String token){return "Bearer "+token;}
}
