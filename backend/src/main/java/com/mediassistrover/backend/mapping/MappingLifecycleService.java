package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Mutations run under the caller's Job lock, in the same transaction as step/outbox changes. */
@Service @RequiredArgsConstructor
public class MappingLifecycleService {
    private final MappingSessionRepository sessions;
    private final MapArtifactRepository artifacts;
    private final RobotCommandOutboxRepository commands;
    private final StepRepository definitions;
    private final JobStepRepository visits;
    private final JobTaskRepository tasks;
    private final MappingProperties properties;
    private final OrchestrationWorkService wakeups;
    private final ObjectMapper json;
    private final Clock clock;

    public static boolean mapping(JobStep step) {
        return step!=null && "EXECUTE_SENSING".equals(step.getStepTypeSnapshot())
                && "MAPPING".equals(step.getResolvedInputsJson().path("sensing_type").asText());
    }
    public static boolean reportsOpen(JsonNode n) {
        return n!=null && !n.isNull() && !n.isMissingNode() && !n.isEmpty()
                && (!n.has("session_open") || n.path("session_open").asBoolean());
    }
    public boolean open(UUID jobId) {return sessions.findByJobId(jobId).map(MappingSession::isPotentiallyOpen).orElse(false);}
    public void requireSupported(Step step) {
        if(!properties.isEnabled())fail("Mapping integration is disabled; keep workflow DRAFT");
        if(step.getWorkflow().getMaxTotalVisits()<2)fail("Mapping must reserve at least one visit for STOP");
        var n=step.getParamsJson();String action=n.path("action").asText();
        if(step.getMaxAttempts()!=1 || !Set.of("START","SAVE","STOP").contains(action)
                || step.getInputMappingJson().has("action") || step.getInputMappingJson().has("sensing_type"))fail("Mapping action must be fixed and maxAttempts=1");
        int min=switch(action){case "START"->properties.getStartTimeoutSec()+10;case "SAVE"->60;default->15;};
        if(properties.getStartTimeoutSec()<5 || properties.getStartTimeoutSec()>120 || step.getTimeoutSec()<min)fail("Mapping timeout is below the action minimum: "+min);
        if(stopDefinition(step.getWorkflow().getWorkflowId())==null)fail("Mapping workflow requires an enabled MAPPING/STOP definition");
    }
    private Step stopDefinition(UUID workflowId) {
        return definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflowId).stream()
                .filter(s->s.isEnabled() && s.getStepType()==StepType.EXECUTE_SENSING
                        && "MAPPING".equals(s.getParamsJson().path("sensing_type").asText())
                        && "STOP".equals(s.getParamsJson().path("action").asText()) && !s.getInputMappingJson().has("action"))
                .findFirst().orElse(null);
    }
    public void prepareSelection(Job job,Step target) {
        if(target.getStepType()!=StepType.EXECUTE_SENSING || !"MAPPING".equals(target.getParamsJson().path("sensing_type").asText()))return;
        requireSupported(target);
        MappingSession session=sessions.findByJobId(job.getJobId()).orElse(null);
        if(session==null) {
            if(!"START".equals(target.getParamsJson().path("action").asText()) || job.getRobot()==null)fail("Mapping must begin with START on an assigned robot");
            if(!sessions.findByRobotIdAndPotentiallyOpenTrue(job.getRobot().getRobotId()).isEmpty())fail("Robot already holds an open mapping session");
            session=sessions.save(MappingSession.builder().sessionId(UUID.randomUUID()).jobId(job.getJobId()).robotId(job.getRobot().getRobotId())
                    .artifactId(UUID.randomUUID()).state("ALLOCATED").createdAt(Instant.now(clock)).build());
        }
        ObjectNode vars=job.getContextJson()==null?json.createObjectNode():((ObjectNode)job.getContextJson()).deepCopy();
        vars.put("mapping_session_id",session.getSessionId().toString()).put("artifact_id",session.getArtifactId().toString());job.setContextJson(vars);
    }
    public MappingSession requireBound(JobStep step) {
        Job job=step.getJobTask().getJob();
        MappingSession session=sessions.findByJobId(job.getJobId()).orElseThrow(()->new BusinessRuleException("Mapping session must be allocated by BE"));
        JsonNode n=step.getResolvedInputsJson();
        if(!job.isBeOrchestrated() || job.getSchemaVersion()!=3 || job.getRobot()==null || !session.getRobotId().equals(job.getRobot().getRobotId())
                || !session.getSessionId().toString().equalsIgnoreCase(n.path("mapping_session_id").asText()))fail("Mapping session is outside Job/robot scope");
        String action=n.path("action").asText();
        if("START".equals(action) && !"ALLOCATED".equals(session.getState()))fail("An issued START cannot be executed again");
        if("SAVE".equals(action) && (!"MAPPING".equals(session.getState())
                || !session.getArtifactId().toString().equalsIgnoreCase(n.path("artifact_id").asText())))fail("SAVE requires the active session and BE artifact ID");
        if("STOP".equals(action) && !session.isPotentiallyOpen())fail("STOP requires a potentially open session");
        return session;
    }
    public void publishing(JobStep step) {
        if(!mapping(step))return;
        MappingSession session=requireBound(step);
        if("START".equals(step.getResolvedInputsJson().path("action").asText())) {
            session.setPotentiallyOpen(true);session.setState("STARTING");
        } else if("SAVE".equals(step.getResolvedInputsJson().path("action").asText()))session.setState("SAVING");
    }
    public void completed(JobStep step,JsonNode output,boolean stopped) {
        if(!mapping(step))return;
        MappingSession session=sessions.findByJobId(step.getJobTask().getJob().getJobId()).orElseThrow();
        if(!stopped)fail("Mapping result requires physicalStopConfirmed=true");
        String action=step.getResolvedInputsJson().path("action").asText();
        if("STOP".equals(action)) {session.setPotentiallyOpen(false);session.setState("STOPPED");session.setClosedAt(Instant.now(clock));}
        else if("START".equals(action))session.setState("MAPPING");
        else {
            var artifact=artifacts.findById(session.getArtifactId()).orElse(null);
            JsonNode metrics=output.path("metrics");
            if(metrics.path("uploaded").asBoolean() && artifact==null)fail("SAVE claims uploaded without a committed artifact receipt");
            if(artifact!=null) artifact.getManifestJson().fields().forEachRemaining(e->{if(!equal(e.getValue(),metrics.path(e.getKey())))fail("SAVE differs from uploaded manifest: "+e.getKey());});
            session.setState(metrics.path("state").asText());
        }
    }
    private boolean equal(JsonNode a,JsonNode b){return a.isNumber() && b.isNumber()?a.decimalValue().compareTo(b.decimalValue())==0:a.equals(b);}

    /** Returns true when terminal completion is deferred until a distinct STOP operation completes. */
    public boolean deferTerminal(Job job,TerminalStatus terminal) {
        if(terminal==TerminalStatus.RECOVERY_REQUIRED || !open(job.getJobId()))return false;
        MappingSession session=sessions.findByJobId(job.getJobId()).orElseThrow();
        if(session.getCleanupTerminal()!=null)return true;
        Step stop=stopDefinition(job.getWorkflow().getWorkflowId());
        if(stop==null)fail("Open mapping has no STOP definition; recovery is required");
        var all=visits.findByJobId(job.getJobId());Instant now=Instant.now(clock);
        for(JobStep old:all)if(old.getSelectedAt()!=null && Set.of(JobStepStatus.PENDING,JobStepStatus.RUNNING).contains(old.getStatus())) {
            old.setStatus(terminal==TerminalStatus.CANCELLED?JobStepStatus.CANCELLED:JobStepStatus.TIMED_OUT);old.setEndedAt(now);
        }
        JobTask task=tasks.findByJobJobIdOrderBySequenceNoAsc(job.getJobId()).stream().filter(t->t.getTask().getTaskId().equals(stop.getTask().getTaskId())).findFirst().orElseThrow();
        JobStep cleanup=all.stream().filter(s->s.getStep().getStepId().equals(stop.getStepId()) && s.getSelectedAt()==null && s.getStatus()==JobStepStatus.PENDING)
                .findFirst().orElseThrow(()->new BusinessRuleException("Mapping STOP visit unavailable; recovery is required"));
        if(all.stream().filter(s->s.getSelectedAt()!=null).count()>=job.getWorkflow().getMaxTotalVisits())fail("No reserved STOP visit; recovery is required");
        cleanup.setMappingCleanup(true);cleanup.setSelectedAt(now);cleanup.setTimeoutSecSnapshot(Math.max(20,stop.getTimeoutSec()));
        cleanup.setExecutionSequenceNo(all.stream().mapToLong(JobStep::getExecutionSequenceNo).max().orElse(0)+1);
        cleanup.setResolvedInputsJson(json.createObjectNode().put("sensing_type","MAPPING").put("action","STOP").put("mapping_session_id",session.getSessionId().toString()));
        session.setCleanupTerminal(terminal.name());job.setSelectedJobStep(cleanup);task.setStatus(JobTaskStatus.RUNNING);task.setEndedAt(null);
        if(task.getStartedAt()==null)task.setStartedAt(now);wakeups.request(job);return true;
    }
    public TerminalStatus cleanupOutcome(JobStep step) {
        var session=sessions.findByJobId(step.getJobTask().getJob().getJobId()).orElseThrow();
        return step.getStatus()==JobStepStatus.SUCCEEDED && !session.isPotentiallyOpen()
                ?TerminalStatus.valueOf(session.getCleanupTerminal()):TerminalStatus.RECOVERY_REQUIRED;
    }
    public boolean telemetryMatches(UUID robotId,JsonNode reported) {
        if(!reportsOpen(reported)) {
            var open=sessions.findByRobotIdAndPotentiallyOpenTrue(robotId);
            if(open.isEmpty() || open.stream().allMatch(s->"STARTING".equals(s.getState())))return true;
            // SAVE failure may close the Pi session before its durable STEP_ERROR
            // reaches BE. Wait for that result while the exact SAVE is in flight;
            // the heartbeat itself never closes the BE session or releases robot.
            boolean inFlight=commands.findByRobotIdAndStateIn(robotId,List.of("PUBLISHING","PUBLISHED","ACKNOWLEDGED","STARTED")).stream()
                    .anyMatch(c->{
                        String action=c.getPayloadJson().path("step").path("params").path("action").asText();
                        if(c.getSendAttempts()<1)return false;
                        return open.stream().anyMatch(s->s.getJobId().equals(c.getJobId()) &&
                            ("STOP".equals(action) || "SAVE".equals(action) && "SAVING".equals(s.getState()) && reported!=null &&
                             s.getSessionId().toString().equals(reported.path("session_id").asText()) &&
                             s.getJobId().toString().equals(reported.path("job_id").asText()) &&
                             Set.of("SAVED_LOCAL","SAVED","ABORTED","STOPPED").contains(reported.path("state").asText())));
                    });
            return inFlight || pendingSaveCleanupMatches(open,reported);
        }
        MappingSession session;
        try{session=sessions.findById(UUID.fromString(reported.path("session_id").asText())).orElse(null);}catch(IllegalArgumentException ex){return false;}
        return session!=null && session.getRobotId().equals(robotId) && session.isPotentiallyOpen()
                && session.getJobId().toString().equalsIgnoreCase(reported.path("job_id").asText());
    }
    /** SAVE's error can commit before cleanup STOP is published. Keep that exact handoff valid;
     * only STOP's result or explicit recovery closes the server session. */
    private boolean pendingSaveCleanupMatches(List<MappingSession> open,JsonNode reported) {
        if(reported==null || !reported.path("session_open").isBoolean() || reported.path("session_open").asBoolean()
                || !Set.of("SAVED_LOCAL","SAVED","ABORTED","STOPPED").contains(reported.path("state").asText()))return false;
        for(var session:open) {
            if(!"SAVING".equals(session.getState()) || session.getCleanupTerminal()==null
                    || !session.getSessionId().toString().equals(reported.path("session_id").asText())
                    || !session.getJobId().toString().equals(reported.path("job_id").asText())
                    || !session.getArtifactId().toString().equals(reported.path("artifact_id").asText()))continue;
            for(var stop:visits.findByJobId(session.getJobId())) {
                var job=stop.getJobTask().getJob();
                if(job.getStatus()!=JobStatus.RUNNING || job.getSelectedJobStep()==null
                        || !stop.getJobStepId().equals(job.getSelectedJobStep().getJobStepId())
                        || !stop.isMappingCleanup() || stop.getStatus()!=JobStepStatus.PENDING || stop.getStartedAt()!=null
                        || !mapping(stop) || !"STOP".equals(stop.getResolvedInputsJson().path("action").asText())
                        || !session.getSessionId().toString().equals(stop.getResolvedInputsJson().path("mapping_session_id").asText()))continue;
                var command=commands.findByJobStepId(stop.getJobStepId()).orElse(null);
                if(command!=null && (!"PENDING".equals(command.getState()) || command.getSendAttempts()!=0
                        || command.getLastPublishAt()!=null || command.getAcknowledgedAt()!=null))continue;
                boolean savedAndStopped=commands.findByJobIdOrderByCreatedAt(session.getJobId()).stream().anyMatch(c->
                        c.getSendAttempts()>0 && "COMPLETED".equals(c.getState()) && Boolean.TRUE.equals(c.getPhysicalStopConfirmed())
                        && "SAVE".equals(c.getPayloadJson().path("step").path("params").path("action").asText())
                        && session.getSessionId().toString().equals(c.getPayloadJson().path("step").path("params").path("mapping_session_id").asText()));
                if(savedAndStopped)return true;
            }
        }
        return false;
    }
    public void reconciled(Job job) {
        sessions.findByJobId(job.getJobId()).ifPresent(s->{s.setPotentiallyOpen(false);s.setState("OPERATOR_RECONCILED");s.setClosedAt(Instant.now(clock));});
    }
    private static void fail(String message){throw new BusinessRuleException(message);}
}
