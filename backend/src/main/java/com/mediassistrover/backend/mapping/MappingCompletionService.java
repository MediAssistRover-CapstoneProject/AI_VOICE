package com.mediassistrover.backend.mapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** A verified stopped completion wakes the existing SAVE/COMMIT/STOP workflow exactly once. */
@Service @RequiredArgsConstructor
public class MappingCompletionService {
    private final JobEventRepository events;private final MappingSessionRepository sessions;
    private final OrchestrationWorkService work;private final ObjectMapper json;private final Clock clock;
    public void observe(Job job,JsonNode report,JsonNode telemetry){
        var selected=job.getSelectedJobStep();var auto=report.path("autonomy");var exploration=auto.path("exploration");
        if(job.getStatus()!=JobStatus.RUNNING||job.getCancelRequestedAt()!=null||selected==null||selected.getStatus()!=JobStepStatus.RUNNING
                ||!"WAIT".equals(selected.getStepTypeSnapshot())||!"MAPPING_SAVE_REQUESTED".equals(selected.getResolvedInputsJson().path("event_type").asText())
                ||!auto.path("save_on_complete").asBoolean()||!auto.path("coverage_complete").asBoolean()||!auto.path("finished").asBoolean()
                ||!"STOPPED".equals(auto.path("state").asText())||!"COVERAGE_COMPLETE".equals(auto.path("reason").asText())
                ||!"COMPLETE".equals(exploration.path("state").asText())||exploration.path("frontier_count").asInt(-1)!=0)return;
        var session=sessions.findByJobId(job.getJobId()).orElse(null);
        if(session==null||!session.isPotentiallyOpen()||!"MAPPING".equals(session.getState())
                ||!session.getSessionId().toString().equals(report.path("session_id").asText())||!job.getJobId().toString().equals(report.path("job_id").asText()))return;
        JsonNode odom=telemetry.path("odom_speed"),gate=telemetry.path("gate"),value=gate.path("value");
        if(value.isTextual()){try{value=json.readTree(value.asText());}catch(Exception e){return;}}
        if(!odom.path("value").isNumber()||odom.path("value").asDouble()>.01||odom.path("age_sec").asDouble(99)>.5
                ||gate.path("age_sec").asDouble(99)>.6||!value.path("applied_velocity").isArray()||value.path("applied_velocity").size()!=3)return;
        for(var v:value.path("applied_velocity"))if(!v.isNumber()||Math.abs(v.asDouble())>.001)return;
        UUID run;try{run=UUID.fromString(auto.path("run_id").asText());}catch(IllegalArgumentException e){return;}
        UUID id=UUID.nameUUIDFromBytes(("MAPPING_COMPLETE:"+session.getSessionId()+":"+run).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(events.existsById(id))return;
        Instant now=Instant.now(clock);var data=json.createObjectNode().put("source","PI_COVERAGE_COMPLETE").put("run_id",run.toString());
        events.saveAndFlush(JobEvent.builder().jobEventId(id).job(job).robot(job.getRobot()).producerType(JobEventProducerType.SERVER)
                .producerSessionId(run).sequenceNo(1).eventType("MAPPING_SAVE_REQUESTED").correlationKey(session.getSessionId().toString())
                .statusScope("WORKFLOW_EVENT").eventDataJson(data).occurredAt(now).receivedAt(now).persistedAt(now).build());
        work.request(job);
    }
}
