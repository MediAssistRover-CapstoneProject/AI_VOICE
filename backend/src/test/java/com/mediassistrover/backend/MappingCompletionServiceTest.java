package com.mediassistrover.backend;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mapping.MappingCompletionService;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MappingCompletionServiceTest {
    ObjectMapper json=new ObjectMapper();UUID jobId=UUID.randomUUID(),sessionId=UUID.randomUUID(),runId=UUID.randomUUID();
    JobEventRepository events=mock(JobEventRepository.class);MappingSessionRepository sessions=mock(MappingSessionRepository.class);
    OrchestrationWorkService work=mock(OrchestrationWorkService.class);
    MappingCompletionService service=new MappingCompletionService(events,sessions,work,json,Clock.systemUTC());
    Job job(){var selected=JobStep.builder().status(JobStepStatus.RUNNING).stepTypeSnapshot("WAIT")
            .resolvedInputsJson(json.createObjectNode().put("event_type","MAPPING_SAVE_REQUESTED")).build();
        when(sessions.findByJobId(jobId)).thenReturn(Optional.of(MappingSession.builder().sessionId(sessionId).jobId(jobId).state("MAPPING").potentiallyOpen(true).build()));
        return Job.builder().jobId(jobId).status(JobStatus.RUNNING).selectedJobStep(selected).build();}
    ObjectNode report(){var root=json.createObjectNode().put("session_id",sessionId.toString()).put("job_id",jobId.toString());
        var a=root.putObject("autonomy").put("save_on_complete",true).put("coverage_complete",true).put("finished",true).put("state","STOPPED").put("reason","COVERAGE_COMPLETE").put("run_id",runId.toString());
        a.putObject("exploration").put("state","COMPLETE").put("frontier_count",0);return root;}
    ObjectNode telemetry(){var t=json.createObjectNode();t.putObject("odom_speed").put("value",0.).put("age_sec",.1);
        var gate=t.putObject("gate").put("age_sec",.1).putObject("value");gate.putArray("applied_velocity").add(0.).add(0.).add(0.);return t;}
    @Test void completedAndStoppedRunRequestsExistingSaveWorkflowOnce(){var job=job();when(events.existsById(any())).thenReturn(false,true);
        service.observe(job,report(),telemetry());service.observe(job,report(),telemetry());
        var captured=org.mockito.ArgumentCaptor.forClass(JobEvent.class);verify(events).saveAndFlush(captured.capture());verify(work).request(job);
        assertThat(captured.getValue().getEventType()).isEqualTo("MAPPING_SAVE_REQUESTED");assertThat(captured.getValue().getCorrelationKey()).isEqualTo(sessionId.toString());}
    @Test void timeoutBlockedOrUnselectedAutosaveCannotCommitCoverage(){var job=job();
        for(String reason:List.of("TIME_LIMIT","ROBOT_FOOTPRINT_NOT_CLEAR_IN_MAP","CONTROL_LEASE_EXPIRED")){
            var report=report();((ObjectNode)report.path("autonomy")).put("reason",reason);service.observe(job,report,telemetry());}
        var report=report();((ObjectNode)report.path("autonomy")).put("save_on_complete",false);service.observe(job,report,telemetry());
        verify(events,never()).saveAndFlush(any());verify(work,never()).request(any());}
    @Test void movingOrStaleRobotNeverStartsSave(){var job=job();var moving=telemetry();((ObjectNode)moving.path("odom_speed")).put("value",.1);
        service.observe(job,report(),moving);var stale=telemetry();((ObjectNode)stale.path("gate")).put("age_sec",5.);service.observe(job,report(),stale);
        verify(events,never()).saveAndFlush(any());}
}
