package com.mediassistrover.backend.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.AlertService;
import com.mediassistrover.backend.vision.VisionDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisionEventServiceTest {
    private final DetectionEventRepository detections = mock(DetectionEventRepository.class);
    private final EmergencyIncidentRepository incidents = mock(EmergencyIncidentRepository.class);
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final AlertService alertService = mock(AlertService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-11-03T08:00:00Z");
    private final UUID robotId = UUID.randomUUID(), eventId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID(), alertId = UUID.randomUUID();
    private final FacilityMap map = FacilityMap.builder().mapId(UUID.randomUUID()).mapName("Test map").build();
    private final Robot robot = Robot.builder().robotId(robotId).robotCode("MAR-TEST")
            .currentMap(map).deviceTokenVersion(3).build();
    private final DevicePrincipal principal = new DevicePrincipal(robotId,"MAR-TEST",3,"test",now.plusSeconds(600));
    private final VisionEventService service = new VisionEventService(detections,incidents,alerts,robots,
            alertService,mapper,Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        when(robots.findByIdForUpdate(robotId)).thenReturn(Optional.of(robot));
        when(detections.findById(eventId)).thenReturn(Optional.empty());
        when(incidents.saveAndFlush(any())).thenAnswer(call -> {
            EmergencyIncident incident=call.getArgument(0); incident.setIncidentId(incidentId); return incident;
        });
        when(alertService.createSystemAlert(anyString(),anyString(),any(),anyString(),any(),any(),
                isNull(),isNull(),isNull(),eq(true),eq(AlertAckPolicy.ANY),any()))
                .thenReturn(Alert.builder().alertId(alertId).build());
    }

    private DetectionRequest request(boolean simulation) {
        ObjectNode metrics=mapper.createObjectNode().put("support_ratio",0.2)
                .put("visible_legs",1).put("baseline_ready",true)
                .put("reason","RAPID_SUPPORT_LOSS_AND_PERSISTENT_LEG_COLLAPSE")
                .put("detection_mode","lower_body").putNull("confidence");
        return new DetectionRequest(eventId,robotId,"SUSPECTED_FALL",now.minusSeconds(1),
                "lower_body_rules_v1",null,1,UUID.randomUUID(),2.0,metrics,simulation);
    }

    private DetectionEvent stored(DetectionRequest request) {
        return DetectionEvent.builder().detectionEventId(eventId).robot(robot).map(map)
                .eventPayloadJson(mapper.valueToTree(request)).receivedAt(now).build();
    }

    @Test void simulationStoresObservationButNeverCreatesIncidentOrAlert() {
        var receipt=service.record(request(true),principal);
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.observationPersisted()).isTrue();
        assertThat(receipt.simulation()).isTrue();
        assertThat(receipt.incidentId()).isNull();
        assertThat(receipt.alertId()).isNull();
        verify(detections).saveAndFlush(argThat(d -> d.getConfidenceScore()==null
                && d.getEventPayloadJson().path("metrics").path("visible_legs").asInt()==1
                && d.getEventPayloadJson().path("metrics").path("baseline_ready").asBoolean()));
        verifyNoInteractions(incidents,alerts,alertService);
    }

    @Test void liveFallCreatesLinkedIncidentAndWarningWithoutInventedConfidence() {
        var receipt=service.record(request(false),principal);
        assertThat(receipt.incidentId()).isEqualTo(incidentId);
        assertThat(receipt.alertId()).isEqualTo(alertId);
        assertThat(receipt.simulation()).isFalse();
        var order=inOrder(detections,incidents,alertService);
        order.verify(detections).saveAndFlush(any());
        order.verify(incidents).saveAndFlush(argThat(i -> i.getDetectionEvent().getDetectionEventId().equals(eventId)
                && i.getSeverity()==EmergencyIncidentSeverity.WARNING && i.getStatus()==EmergencyIncidentStatus.OPEN));
        order.verify(alertService).createSystemAlert(eq("VISION:"+eventId),eq("SUSPECTED_FALL"),eq(AlertSeverity.WARNING),
                contains("dấu hiệu té ngã"),argThat(data -> data.path("metrics").path("support_ratio").asDouble()==0.2
                        && !data.path("simulation").asBoolean()),any(),isNull(),isNull(),isNull(),
                eq(true),eq(AlertAckPolicy.ANY),eq(now.plusSeconds(600)));
    }

    @Test void identicalRetryReturnsExistingIdsAndDoesNotWriteAgain() {
        var request=request(false);
        when(detections.findById(eventId)).thenReturn(Optional.of(stored(request)));
        when(incidents.findByDetectionEventDetectionEventId(eventId)).thenReturn(Optional.of(
                EmergencyIncident.builder().incidentId(incidentId).build()));
        when(alerts.findByOperationKey("VISION:"+eventId)).thenReturn(Optional.of(Alert.builder().alertId(alertId).build()));
        var receipt=service.record(request,principal);
        assertThat(receipt.duplicate()).isTrue();
        assertThat(receipt.incidentId()).isEqualTo(incidentId);
        assertThat(receipt.alertId()).isEqualTo(alertId);
        verify(detections,never()).saveAndFlush(any());
        verify(incidents,never()).saveAndFlush(any());
        verifyNoInteractions(alertService);
    }

    @Test void changedContentOrChangedSimulationWithSameIdConflicts() {
        var request=request(true);
        when(detections.findById(eventId)).thenReturn(Optional.of(stored(request)));
        var changed=new DetectionRequest(request.eventId(),request.robotId(),request.eventType(),request.detectedAt(),
                request.algorithm(),request.confidence(),request.personId(),request.sessionId(),request.sampleTimeSec(),request.metrics(),false);
        assertThatThrownBy(() -> service.record(changed,principal)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(alertService);
    }

    @Test void persistedJsonNumericRepresentationDoesNotBreakIdempotency() {
        var request=request(true);
        var existing=stored(request);
        ((ObjectNode)existing.getEventPayloadJson().path("metrics"))
                .put("support_ratio",new java.math.BigDecimal("0.20000"));
        ((ObjectNode)existing.getEventPayloadJson()).put("sampleTimeSec",2);
        when(detections.findById(eventId)).thenReturn(Optional.of(existing));
        assertThat(service.record(request,principal).duplicate()).isTrue();
        verify(detections,never()).saveAndFlush(any());
    }

    @Test void foreignRobotCannotUseDeviceIdentity() {
        var other=new DevicePrincipal(UUID.randomUUID(),"OTHER",3,"test",now.plusSeconds(600));
        assertThatThrownBy(() -> service.record(request(true),other))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(robots,detections,incidents,alerts,alertService);
    }

    @Test void expiredDeviceTokenCannotIngest() {
        var expired=new DevicePrincipal(robotId,"MAR-TEST",3,"test",now);
        assertThatThrownBy(() -> service.record(request(true),expired))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(detections,never()).saveAndFlush(any());
    }

    @Test void missingAssignedMapIsRejectedWithoutGlobalFallback() {
        robot.setCurrentMap(null);
        assertThatThrownBy(() -> service.record(request(true),principal)).hasMessageContaining("VISION_MAP_UNAVAILABLE");
        verify(detections,never()).saveAndFlush(any());
        verifyNoInteractions(alertService);
    }

    @Test void unsupportedAndNestedMetricsAreRejected() {
        var request=request(true);
        ((ObjectNode)request.metrics()).putArray("landmarks").add(1);
        assertThatThrownBy(() -> service.record(request,principal)).hasMessageContaining("Unsupported vision metric");
        verifyNoInteractions(robots,detections,alertService);
    }

    @Test void simulationMustBeExplicit() {
        var request=request(true);
        var missing=new DetectionRequest(request.eventId(),request.robotId(),request.eventType(),request.detectedAt(),
                request.algorithm(),null,1,request.sessionId(),2.0,request.metrics(),null);
        assertThatThrownBy(() -> service.record(missing,principal)).hasMessageContaining("simulation");
    }

    @Test void wrongMetricTypesCannotMasqueradeAsConfidence() {
        var request=request(true);
        ((ObjectNode)request.metrics()).put("confidence","high");
        assertThatThrownBy(() -> service.record(request,principal)).hasMessageContaining("numeric metric");
    }
}
