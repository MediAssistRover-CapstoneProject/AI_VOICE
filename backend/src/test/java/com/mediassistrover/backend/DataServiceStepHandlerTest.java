package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import com.mediassistrover.backend.service.impl.DataServiceStepHandler;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataServiceStepHandlerTest {
    @Test void createFallIncidentIsIdempotentByDetectionEvent(){
        HospitalInformationQueryService hospital=mock(HospitalInformationQueryService.class);
        EndpointRepository endpoints=mock(EndpointRepository.class);
        EndpointGroupMemberRepository members=mock(EndpointGroupMemberRepository.class);
        DetectionEventRepository detections=mock(DetectionEventRepository.class);
        EmergencyIncidentRepository incidents=mock(EmergencyIncidentRepository.class);
        ObjectMapper json=new ObjectMapper();
        DataServiceStepHandler handler=new DataServiceStepHandler(hospital,endpoints,members,detections,incidents,json,Clock.systemUTC(),mock(com.mediassistrover.backend.service.NavigationTargetService.class),mock(com.mediassistrover.backend.service.StaffIncidentReviewPolicy.class));

        UUID robotId=UUID.randomUUID(),detectionId=UUID.randomUUID(),incidentId=UUID.randomUUID();
        Robot robot=Robot.builder().robotId(robotId).build();
        DetectionEvent detection=DetectionEvent.builder().detectionEventId(detectionId).robot(robot).map(FacilityMap.builder().build())
                .eventType(DetectionEventType.SUSPECTED_FALL).detectedAt(Instant.now()).build();
        EmergencyIncident saved=EmergencyIncident.builder().incidentId(incidentId).detectionEvent(detection).build();
        when(detections.findById(detectionId)).thenReturn(Optional.of(detection));
        when(incidents.findByDetectionEventDetectionEventId(detectionId)).thenReturn(Optional.empty(),Optional.of(saved));
        when(incidents.saveAndFlush(any())).thenAnswer(invocation->{EmergencyIncident value=invocation.getArgument(0);value.setIncidentId(incidentId);return value;});
        Job job=Job.builder().robot(robot).build();Step definition=Step.builder().stepType(StepType.DATA_SERVICE).stepCode("CREATE_INCIDENT").build();
        JobStep step=JobStep.builder().jobTask(JobTask.builder().job(job).build()).step(definition).stepCodeSnapshot("CREATE_INCIDENT").build();
        ObjectNode input=json.createObjectNode().put("service_key","CREATE_FALL_INCIDENT").put("detection_event_id",detectionId.toString());

        var created=handler.execute(step,input);var replay=handler.execute(step,input);
        assertThat(created.result().path("data").path("incident_id").asText()).isEqualTo(incidentId.toString());
        assertThat(created.result().path("data").path("created").asBoolean()).isTrue();
        assertThat(replay.result().path("data").path("created").asBoolean()).isFalse();
        verify(incidents,times(1)).saveAndFlush(any());
    }
}
