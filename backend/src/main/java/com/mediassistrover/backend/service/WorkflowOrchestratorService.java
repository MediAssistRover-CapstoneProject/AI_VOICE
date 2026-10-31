package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.validation.StepExecutionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** One durable unit of work per transaction; the selected graph edge is the only source of next steps. */
@Service @RequiredArgsConstructor
public class WorkflowOrchestratorService {
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final JobTaskRepository tasks;
    private final RobotRepository robots;
    private final JobEventRepository events;
    private final WorkflowOrchestrationWorkRepository work;
    private final OrchestrationWorkService wakeups;
    private final WorkflowRuntimeTransitionService transitions;
    private final JobStepRuntimeGuard guard;
    private final JobStepInputResolver resolver;
    private final ServerStepExecutionService server;
    private final com.mediassistrover.backend.mqtt.MqttStepDispatchService mqttDispatch;
    private final com.mediassistrover.backend.mqtt.MqttOperationControlService mqttControl;
    private final WorkflowEventService workflowEvents;
    private final ApplicationEventPublisher updates;
    private final ObjectMapper json;
    private final Clock clock;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final StepExecutionPolicy executionPolicy;

    @Transactional
    public void advance(UUID jobId) {
        Job job=jobs.lockById(jobId).orElseThrow();
        if (!job.isBeOrchestrated()) return;
        WorkflowOrchestrationWork row=work.findForUpdate(jobId).orElse(null);
        if (row==null || row.getRequestedGeneration()<=row.getProcessedGeneration()) return;
        long generation=row.getRequestedGeneration();
        row.setProcessedGeneration(generation);
        Instant now=Instant.now(clock);
        if (job.getStatus()==JobStatus.QUEUED && job.getCancelRequestedAt()!=null) {
            finish(job,TerminalStatus.CANCELLED); return;
        }
        if (job.getStatus()==JobStatus.QUEUED) {
            if (job.getRobot()==null) return; // assign-robot will wake the same durable work
            Robot robot=robots.findByIdForUpdate(job.getRobot().getRobotId()).orElseThrow();
            if (robot.getStatus()!=RobotStatus.IDLE) return;
            if (jobs.findByRobotRobotIdAndStatusIn(robot.getRobotId(),List.of(JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED))
                    .stream().anyMatch(other->!other.getJobId().equals(jobId))) return;
            job.setStatus(JobStatus.RUNNING); job.setAcceptedAt(now); job.setStartedAt(now); robot.setStatus(RobotStatus.BUSY);
            if (job.getServiceRequest()!=null) job.getServiceRequest().setStatus(ServiceRequestStatus.IN_PROGRESS);
            event(job,null,"BE_JOB_STARTED",json.createObjectNode());
        }
        if (job.getStatus()!=JobStatus.RUNNING) return;
        if(mqttControl.handle(job,job.getSelectedJobStep()))return;
        JobStep cleanup=job.getSelectedJobStep();
        if(cleanup!=null && cleanup.isMappingCleanup()) {
            if(cleanup.getStatus()==JobStepStatus.PENDING)mqttDispatch.dispatch(cleanup);
            else if(cleanup.getStatus()!=JobStepStatus.RUNNING)finish(job,mapping.cleanupOutcome(cleanup));
            updates.publishEvent(new RuntimeChanged(jobId));return;
        }
        List<JobStep> visits=steps.findByJobId(jobId);
        if (visits.stream().anyMatch(s->s.getStatus()==JobStepStatus.RECOVERY_REQUIRED)) { finish(job,TerminalStatus.RECOVERY_REQUIRED); return; }
        if (job.getCancelRequestedAt()!=null) {
            // Active Pi operations are handled above; completed stop evidence governs a server/UI wait.
            finish(job,mqttControl.hasUnresolvedPiWork(visits)?TerminalStatus.RECOVERY_REQUIRED:TerminalStatus.CANCELLED); return;
        }
        if (!job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(now)) {
            job.setErrorCode("WORKFLOW_DURATION_EXCEEDED");
            finish(job,mqttControl.hasUnresolvedPiWork(visits)?TerminalStatus.RECOVERY_REQUIRED:TerminalStatus.FAILED); return;
        }
        JobStep selected=job.getSelectedJobStep();
        if (selected!=null && selected.getStatus()==JobStepStatus.RUNNING) {
            if (!executionPolicy.isServer(selected,selected.getResolvedInputsJson())) return;
            if (!selected.getStartedAt().plusSeconds(selected.getTimeoutSecSnapshot()).isAfter(now)) {
                selected.setStatus(JobStepStatus.TIMED_OUT); selected.setEndedAt(now); selected.setErrorCode("STEP_TIMEOUT");
                selected.setErrorDetailsJson(json.createObjectNode().put("reason","STEP_TIMEOUT"));
                event(job,selected,"STEP_TIMEOUT",selected.getErrorDetailsJson());
            } else {
                if ("WAIT".equals(selected.getStepTypeSnapshot())) workflowEvents.consumeAvailable(selected);
                updates.publishEvent(new RuntimeChanged(jobId)); return;
            }
        }
        Step target;
        if (selected==null) target=job.getWorkflow().getStartStep();
        else if (Set.of(JobStepStatus.SUCCEEDED,JobStepStatus.FAILED,JobStepStatus.TIMED_OUT).contains(selected.getStatus())
                || selected.getStatus()==JobStepStatus.CANCELLED && "STEP_TIMEOUT".equals(selected.getInterruptionReason())) {
            StepTransition route=transitions.resolve(selected);
            if (route.getToStep()==null) { finish(job,route.getTerminalStatus()); return; }
            target=route.getToStep();
        } else {
            if (selected.getStatus()==JobStepStatus.PENDING && executionPolicy.isServer(selected,selected.getResolvedInputsJson()))
                server.execute(selected);
            else if(selected.getStatus()==JobStepStatus.PENDING) mqttDispatch.dispatch(selected);
            updates.publishEvent(new RuntimeChanged(jobId)); return;
        }
        if (target==null || !target.isEnabled() || !target.getWorkflow().getWorkflowId().equals(job.getWorkflow().getWorkflowId()))
            throw new BusinessRuleException("Selected graph target is invalid");
        long total=visits.stream().filter(s->s.getSelectedAt()!=null).count();
        long count=visits.stream().filter(s->s.getSelectedAt()!=null && s.getStep().getStepId().equals(target.getStepId())).count();
        boolean targetIsStop=target.getStepType()==StepType.EXECUTE_SENSING && "MAPPING".equals(target.getParamsJson().path("sensing_type").asText()) && "STOP".equals(target.getParamsJson().path("action").asText());
        int totalLimit=job.getWorkflow().getMaxTotalVisits()-(mapping.open(jobId) && !targetIsStop?1:0);
        if (total>=totalLimit || count>=target.getMaxVisits()) {
            job.setErrorCode("WORKFLOW_VISIT_LIMIT");
            finish(job,mqttControl.hasUnresolvedPiWork(visits)?TerminalStatus.RECOVERY_REQUIRED:TerminalStatus.FAILED); return;
        }
        JobStep next=visits.stream().filter(s->s.getStep().getStepId().equals(target.getStepId())
                && s.getStatus()==JobStepStatus.PENDING && s.getSelectedAt()==null).findFirst().orElse(null);
        if (next==null) {
            JobTask task=tasks.findByJobJobIdOrderBySequenceNoAsc(jobId).stream()
                    .filter(t->t.getTask().getTaskId().equals(target.getTask().getTaskId())).findFirst().orElseThrow();
            int visit=visits.stream().filter(s->s.getStep().getStepId().equals(target.getStepId())).mapToInt(JobStep::getVisitNo).max().orElse(0)+1;
            next=steps.saveAndFlush(JobStep.builder().jobTask(task).step(target).visitNo(visit)
                    .stepCodeSnapshot(target.getStepCode()).stepTypeSnapshot(target.getStepType().name())
                    .executionSequenceNo(visits.stream().mapToLong(JobStep::getExecutionSequenceNo).max().orElse(0)+1)
                    .status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(target.getTimeoutSec())
                    .resolvedInputsJson(json.createObjectNode()).operationKey(jobId+":"+target.getStepCode()+":"+visit).build());
        } else next.setExecutionSequenceNo(visits.stream().mapToLong(JobStep::getExecutionSequenceNo).max().orElse(0)+1);
        guard.requireStartAllowed(next);
        mapping.prepareSelection(job,target);
        job.setSelectedJobStep(next);
        next.setSelectedAt(now);
        next.setResolvedInputsJson(resolver.resolve(next).deepCopy());
        JobTask task=next.getJobTask(); task.setStatus(JobTaskStatus.RUNNING); task.setEndedAt(null);
        if (task.getStartedAt()==null) task.setStartedAt(now);
        event(job,next,"BE_STEP_SELECTED",next.getResolvedInputsJson());
        wakeups.request(job);
        updates.publishEvent(new RuntimeChanged(jobId));
    }

    /** Called in a new transaction after a rejected server execution was rolled back. */
    @Transactional
    public void reject(UUID jobId,String reason) {
        Job job=jobs.lockById(jobId).orElseThrow();
        if (!job.isBeOrchestrated() || !Set.of(JobStatus.RUNNING,JobStatus.QUEUED).contains(job.getStatus())) return;
        JobStep selected=job.getSelectedJobStep();
        if (selected==null || selected.getStatus()!=JobStepStatus.PENDING) {
            job.setErrorCode("ORCHESTRATION_REJECTED"); job.setFailureReason(reason); finish(job,TerminalStatus.RECOVERY_REQUIRED); return;
        }
        selected.setStartedAt(Instant.now(clock)); selected.setEndedAt(Instant.now(clock));
        selected.setStatus(JobStepStatus.FAILED); selected.setErrorCode("SERVER_STEP_REJECTED");
        selected.setErrorDetailsJson(json.createObjectNode().put("message",reason));
        event(job,selected,"SERVER_STEP_REJECTED",selected.getErrorDetailsJson()); wakeups.request(job);
    }

    private boolean hasPhysicalWork(List<JobStep> visits) {
        return visits.stream().anyMatch(s->(s.getStartedAt()!=null || !s.getResolvedInputsJson().isEmpty())
                && !executionPolicy.isServer(s,s.getResolvedInputsJson()));
    }
    private void finish(Job job,TerminalStatus terminal) {
        JobStep finishingStep=job.getSelectedJobStep();
        if(com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(finishingStep) && "STOP".equals(finishingStep.getResolvedInputsJson().path("action").asText())
                && finishingStep.getStatus()!=JobStepStatus.SUCCEEDED && mapping.open(job.getJobId()))terminal=TerminalStatus.RECOVERY_REQUIRED;
        if(mapping.deferTerminal(job,terminal)) {updates.publishEvent(new RuntimeChanged(job.getJobId()));return;}
        Instant now=Instant.now(clock); job.setStatus(JobStatus.valueOf(terminal.name()));
        if (terminal!=TerminalStatus.RECOVERY_REQUIRED) job.setEndedAt(now);
        List<JobStep> all=steps.findByJobId(job.getJobId());
        if (terminal==TerminalStatus.CANCELLED || terminal==TerminalStatus.FAILED)
            for (JobStep step:all) if (step.getStartedAt()!=null && Set.of(JobStepStatus.RUNNING,JobStepStatus.RETRYING).contains(step.getStatus())) {
                step.setStatus(terminal==TerminalStatus.CANCELLED?JobStepStatus.CANCELLED:JobStepStatus.TIMED_OUT);
                step.setEndedAt(now);
            }
        for (JobTask task:tasks.findByJobJobIdOrderBySequenceNoAsc(job.getJobId())) {
            var selected=all.stream().filter(s->s.getJobTask().getJobTaskId().equals(task.getJobTaskId()) && s.getSelectedAt()!=null).toList();
            if (selected.isEmpty()) task.setStatus(JobTaskStatus.NOT_VISITED);
            else if (terminal!=TerminalStatus.RECOVERY_REQUIRED) {
                boolean succeeded=selected.stream().allMatch(s->s.getStatus()==JobStepStatus.SUCCEEDED);
                task.setStatus(succeeded?JobTaskStatus.SUCCEEDED:terminal==TerminalStatus.CANCELLED?JobTaskStatus.CANCELLED:JobTaskStatus.FAILED);
                task.setEndedAt(now);
            }
        }
        if (terminal==TerminalStatus.SUCCEEDED && job.getSelectedJobStep()!=null) {
            JsonNode root=resolver.runtimeContext(job.getSelectedJobStep());
            job.setResultJson(resolveMapping(job.getWorkflow().getOutputMappingJson(),root));
        }
        if (job.getServiceRequest()!=null) {
            job.getServiceRequest().setStatus(terminal==TerminalStatus.SUCCEEDED?ServiceRequestStatus.COMPLETED:ServiceRequestStatus.valueOf(terminal.name()));
            if (terminal!=TerminalStatus.RECOVERY_REQUIRED) job.getServiceRequest().setCompletedAt(now);
        }
        if (job.getRobot()!=null) {
            if (terminal==TerminalStatus.RECOVERY_REQUIRED) job.getRobot().setStatus(RobotStatus.ERROR);
            else if (job.getRobot().getStatus()==RobotStatus.BUSY) job.getRobot().setStatus(RobotStatus.IDLE);
        }
        event(job,job.getSelectedJobStep(),"BE_JOB_"+terminal.name(),json.createObjectNode());
        updates.publishEvent(new RuntimeChanged(job.getJobId()));
    }
    private JsonNode resolveMapping(JsonNode mapping,JsonNode root) {
        if (mapping.isObject()) {
            if (mapping.size()==1 && mapping.has("$ref")) {
                JsonNode value=root.at(mapping.path("$ref").asText());
                if (value.isMissingNode()) throw new BusinessRuleException("Unresolved workflow output "+mapping.path("$ref").asText());
                return value.deepCopy();
            }
            ObjectNode output=json.createObjectNode(); mapping.fields().forEachRemaining(e->output.set(e.getKey(),resolveMapping(e.getValue(),root))); return output;
        }
        if (mapping.isArray()) { var output=json.createArrayNode(); mapping.forEach(value->output.add(resolveMapping(value,root))); return output; }
        return mapping.deepCopy();
    }
    private void event(Job job,JobStep step,String type,JsonNode data) {
        Instant now=Instant.now(clock);
        events.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(job).robot(job.getRobot()).jobStep(step)
                .jobTask(step==null?null:step.getJobTask()).producerType(JobEventProducerType.SERVER)
                .producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType(type).statusScope(step==null?"JOB":"JOB_STEP")
                .newStatus(step==null?job.getStatus().name():step.getStatus().name()).eventDataJson(data.deepCopy())
                .occurredAt(now).receivedAt(now).persistedAt(now).build());
    }
}
