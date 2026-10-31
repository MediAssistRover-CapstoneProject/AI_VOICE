package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Caller holds Job lock; cancellation intent and outbox are committed with orchestration work. */
@Service @RequiredArgsConstructor
public class MqttOperationControlService {
    private final RobotCommandOutboxRepository commands;
    private final RobotMqttSessionRepository sessions;
    private final RobotRepository robots;
    private final JobStepRepository steps;
    private final JobTaskRepository tasks;
    private final JobEventRepository events;
    private final MqttSessionRegistry registry;
    private final MqttProperties properties;
    private final MqttJobSafety safety;
    private final MqttContractValidator contracts;
    private final MqttWire wire;
    private final OrchestrationWorkService wakeups;
    private final org.springframework.context.ApplicationEventPublisher updates;
    private final Clock clock;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final StepExecutionPolicy executionPolicy;
    private static final Set<JobStepStatus> TERMINAL=EnumSet.of(JobStepStatus.SUCCEEDED,JobStepStatus.FAILED,JobStepStatus.TIMED_OUT,JobStepStatus.CANCELLED);
    public boolean hasUnresolvedPiWork(List<JobStep> visits) {
        for(JobStep step:visits)if(!executionPolicy.isServer(step,step.getResolvedInputsJson())) {
            var command=commands.findByJobStepId(step.getJobStepId()).orElse(null);
            if(command!=null && command.getSendAttempts()>0 && !Boolean.TRUE.equals(command.getPhysicalStopConfirmed())
                    && (!TERMINAL.contains(step.getStatus()) || Set.of("MOVE","CONTROL_HARDWARE").contains(step.getStepTypeSnapshot())
                    || com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(step)))return true;
        }
        return false;
    }

    public boolean handle(Job job,JobStep step) {
        if(step==null || executionPolicy.isServer(step,step.getResolvedInputsJson()))return false;
        if(job.getRobot()!=null)robots.findByIdForUpdate(job.getRobot().getRobotId()).orElseThrow();
        var session=job.getRobot()==null?null:sessions.lock(job.getRobot().getRobotId()).orElse(null);
        var execute=commands.findByJobStepId(step.getJobStepId()).orElse(null);
        Instant now=Instant.now(clock);
        boolean jobExpired=job.getStartedAt()!=null && !job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(now);
        String reason=step.getInterruptionReason();
        if(reason==null) {
            if(job.getCancelRequestedAt()!=null && !step.isMappingCleanup())reason="JOB_CANCEL";
            else if(jobExpired && !step.isMappingCleanup())reason="WORKFLOW_TIMEOUT";
            else if(!TERMINAL.contains(step.getStatus()) && (step.getStartedAt()!=null && !step.getStartedAt().plusSeconds(step.getTimeoutSecSnapshot()).isAfter(now)
                    || execute!=null && "ACKNOWLEDGED".equals(execute.getState()) && !execute.getExpiresAt().isAfter(now)))reason="STEP_TIMEOUT";
            else return false;
            step.setInterruptionReason(reason);step.setInterruptionRequestedAt(now);
            record(job,step,"BE_OPERATION_INTERRUPTED",wire.object().put("reason",reason));
        }
        if(TERMINAL.contains(step.getStatus()) && (execute==null || Boolean.TRUE.equals(execute.getPhysicalStopConfirmed()))) {
            completeStopped(job,step,execute,reason);return !"STEP_TIMEOUT".equals(reason);
        }
        if(execute==null || execute.getSendAttempts()==0) {
            if(execute!=null) {execute.setState("ABANDONED");execute.setPhysicalStopConfirmed(true);}
            step.setStatus("JOB_CANCEL".equals(reason)?JobStepStatus.CANCELLED:JobStepStatus.TIMED_OUT);step.setEndedAt(now);step.setErrorCode("BE_"+reason+"_BEFORE_PUBLISH");
            completeStopped(job,step,execute,reason);return !"STEP_TIMEOUT".equals(reason);
        }
        var cancel=commands.findByJobStepIdAndCommandType(step.getJobStepId(),"CANCEL_STEP").orElse(null);
        if(cancel==null) {
            if(session==null) {safety.recovery(job,"Cannot cancel without a known MQTT session");return true;}
            UUID id=UUID.randomUUID();
            ObjectNode payload=wire.object().put("protocolVersion","1.0").put("contract","MAR-STEP-2").put("messageType","COMMAND")
                    .put("commandId",id.toString()).put("correlationId",id.toString()).put("robotId",job.getRobot().getRobotId().toString())
                    .put("transportSessionId",session.getTransportSessionId().toString()).put("commandType","CANCEL_STEP").put("expiresAt",now.plusSeconds(properties.getCommandTtlSec()).toString())
                    .put("jobId",job.getJobId().toString()).put("jobStepId",step.getJobStepId().toString());
            contracts.command(payload);
            cancel=commands.save(RobotCommandOutbox.builder().commandId(id).commandType("CANCEL_STEP").jobId(job.getJobId()).jobStepId(step.getJobStepId())
                    .robotId(job.getRobot().getRobotId()).operationKey(step.getOperationKey()).interruptionReason(reason).payloadJson(payload)
                    .createdAt(now).expiresAt(now.plusSeconds(properties.getCommandTtlSec())).state("PENDING").build());
        }
        if(!cancel.getExpiresAt().isAfter(now) && !Boolean.TRUE.equals(cancel.getPhysicalStopConfirmed())) {
            cancel.setState("RECOVERY_REQUIRED");safety.recovery(job,"Cancellation deadline passed without confirmed stop");
        } else if(!registry.fresh(session)) {
            // A new HELLO may reconcile the same operation; no execution is replayed here.
            if(session==null || session.isClosed())safety.recovery(job,"Cancel waits for disconnected operation recovery");
        }
        return true;
    }

    public void completeStopped(Job job,JobStep step,RobotCommandOutbox execute,String reason) {
        var cancel=commands.findByJobStepIdAndCommandType(step.getJobStepId(),"CANCEL_STEP").orElse(null);
        if(cancel!=null) {cancel.setState("STOP_CONFIRMED");cancel.setPhysicalStopConfirmed(true);}
        if(execute!=null && !Set.of("ABANDONED","RESOLVED_BY_STAFF").contains(execute.getState()))execute.setState("COMPLETED");
        // An existing recovery decision is never silently undone by late ACK/result.
        if(job.getStatus()==JobStatus.RECOVERY_REQUIRED)return;
        if("JOB_CANCEL".equals(reason) || "WORKFLOW_TIMEOUT".equals(reason)) {
            if(mapping.open(job.getJobId())) {mapping.deferTerminal(job,"JOB_CANCEL".equals(reason)?TerminalStatus.CANCELLED:TerminalStatus.FAILED);updates.publishEvent(new RuntimeChanged(job.getJobId()));return;}
            Instant now=Instant.now(clock);JobStatus status="JOB_CANCEL".equals(reason)?JobStatus.CANCELLED:JobStatus.FAILED;
            job.setStatus(status);job.setEndedAt(now);job.setErrorCode("JOB_CANCEL".equals(reason)?null:"WORKFLOW_DURATION_EXCEEDED");
            if(job.getServiceRequest()!=null){job.getServiceRequest().setStatus(ServiceRequestStatus.valueOf(status.name()));
                if(status==JobStatus.CANCELLED)job.getServiceRequest().setCancelledAt(now);else job.getServiceRequest().setCompletedAt(now);}
            for(JobStep visit:steps.findByJobId(job.getJobId()))if(visit.getSelectedAt()!=null && Set.of(JobStepStatus.RUNNING,JobStepStatus.PENDING).contains(visit.getStatus())) {
                visit.setStatus(status==JobStatus.CANCELLED?JobStepStatus.CANCELLED:JobStepStatus.TIMED_OUT);visit.setEndedAt(now);
            }
            for(JobTask task:tasks.findByJobJobIdOrderBySequenceNoAsc(job.getJobId())) {
                if(task.getStartedAt()==null)task.setStatus(JobTaskStatus.NOT_VISITED);
                else {task.setStatus(status==JobStatus.CANCELLED?JobTaskStatus.CANCELLED:JobTaskStatus.FAILED);task.setEndedAt(now);}
            }
            if(job.getRobot()!=null && job.getRobot().getStatus()==RobotStatus.BUSY)job.getRobot().setStatus(RobotStatus.IDLE);
            record(job,step,"BE_JOB_"+status.name(),wire.object().put("reason",reason));
        }
        wakeups.request(job);updates.publishEvent(new RuntimeChanged(job.getJobId()));
    }
    private void record(Job job,JobStep step,String type,ObjectNode data) {
        Instant now=Instant.now(clock);events.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(job).jobStep(step).jobTask(step.getJobTask()).robot(job.getRobot())
                .producerType(JobEventProducerType.SERVER).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType(type).statusScope("JOB_STEP")
                .newStatus(step.getStatus().name()).eventDataJson(data).occurredAt(now).receivedAt(now).persistedAt(now).build());
        updates.publishEvent(new RuntimeChanged(job.getJobId()));
    }
}
