package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real disposable MySQL. No alerts or credentials from the working database. */
@Transactional
@org.springframework.test.context.TestPropertySource(properties={
    "app.mqtt.enabled=false", "app.mqtt.dispatch-enabled=false",
    "app.operations.scheduler-enabled=false", "app.assistant.enabled=false",
    "app.bootstrap.admin-username=", "app.bootstrap.admin-password=", "app.bootstrap.admin-email=",
    "app.bootstrap.admin-account-code=", "app.bootstrap.admin-full-name="
})
class VisionMySqlIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired DeviceJwtTokenService deviceTokens;
    @Autowired JwtTokenService userTokens;
    @Autowired DetectionEventRepository detections;
    @Autowired AlertRepository alerts;
    MqttTestFixture fixture;
    String device;

    @BeforeEach void prepare() {
        fixture=MqttTestFixture.create(em,json,StepType.MOVE,null);
        Robot robot=em.find(Robot.class,fixture.robotId);robot.setDeviceSecretHash("test-only");
        em.flush();device=deviceTokens.issue(robot).value();
    }
    ObjectNode event(boolean simulation) {
        ObjectNode node=json.createObjectNode().put("eventId",UUID.randomUUID().toString())
                .put("robotId",fixture.robotId.toString()).put("eventType","SUSPECTED_FALL")
                .put("detectedAt",Instant.now().minusSeconds(1).toString()).put("algorithm","lower_body_rules_v1")
                .putNull("confidence").put("personId",1).put("sessionId",UUID.randomUUID().toString())
                .put("sampleTimeSec",1.0).put("simulation",simulation);
        node.putObject("metrics").put("support_ratio",.2).put("visible_legs",1).put("baseline_ready",true);
        return node;
    }
    JsonNode send(ObjectNode body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/pi/vision/detections")
                .header("Authorization","Bearer "+device).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void simulationPersistsOnlyDetectionAndRetrySurvivesMysqlJsonRoundTrip() throws Exception {
        ObjectNode body=event(true);JsonNode first=send(body);em.flush();em.clear();
        JsonNode second=send(body);
        assertThat(first.path("observationPersisted").asBoolean()).isTrue();
        assertThat(first.path("alertId").isNull()).isTrue();assertThat(second.path("duplicate").asBoolean()).isTrue();
        UUID id=UUID.fromString(body.path("eventId").asText());assertThat(detections.findById(id)).isPresent();
        assertThat(alerts.findByOperationKey("VISION:"+id)).isEmpty();
        body.withObject("metrics").put("visible_legs",2);
        mvc.perform(post("/api/v1/pi/vision/detections").header("Authorization","Bearer "+device)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isConflict());
    }
    @Test void liveEventLinksMysqlRowsAndStaffCanReadThenAcknowledgeWarning() throws Exception {
        String code=UUID.randomUUID().toString();
        Account staff=Account.builder().accountCode("VISION-"+code).username("vision-"+code).fullName("Isolated test staff")
                .role(Role.STAFF).authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE).passwordHash("unused").build();em.persist(staff);em.flush();
        String token=userTokens.issueAccessToken(staff).value();
        JsonNode receipt=send(event(false));UUID detectionId=UUID.fromString(receipt.path("eventId").asText());
        UUID alertId=UUID.fromString(receipt.path("alertId").asText());em.flush();em.clear();
        Alert alert=alerts.findById(alertId).orElseThrow();
        assertThat(alert.getDetectionEvent().getDetectionEventId()).isEqualTo(detectionId);
        assertThat(alert.getIncident().getIncidentId().toString()).isEqualTo(receipt.path("incidentId").asText());
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.WARNING);
        Number count=(Number)em.createNativeQuery("SELECT COUNT(*) FROM AlertRecipient WHERE alert_id=:id")
                .setParameter("id",alertId).getSingleResult();assertThat(count.intValue()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/alerts/"+alertId).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        em.flush();em.clear();
        String delivered=(String)em.createNativeQuery("SELECT delivery_status FROM AlertRecipient WHERE alert_id=:a AND account_id=:u")
                .setParameter("a",alertId).setParameter("u",staff.getAccountId()).getSingleResult();assertThat(delivered).isEqualTo("DELIVERED");
        mvc.perform(post("/api/v1/alerts/"+alertId+"/acknowledge").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
    }
    @Test void missingMapRejectsBeforePersistingObservation() throws Exception {
        Robot r=em.find(Robot.class,fixture.robotId);r.setCurrentMap(null);em.flush();ObjectNode body=event(true);
        mvc.perform(post("/api/v1/pi/vision/detections").header("Authorization","Bearer "+device)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isUnprocessableEntity());
        assertThat(detections.findById(UUID.fromString(body.path("eventId").asText()))).isEmpty();
    }
}
