package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import com.mediassistrover.backend.validation.StepResultContractValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class MqttRuntimeEvents {
    private final JobStepRepository steps;
    private final RobotCommandOutboxRepository commands;
    private final JobEventRepository events;
    private final JobTaskRepository tasks;
    private final StepResultContractValidator outputs;
    private final OrchestrationWorkService wakeups;
    private final MqttJobSafety safety;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher updates;
    private final MqttOperationControlService control;
    private final MqttWire wire;
    private final com.mediassistrover.backend.service.NavigationTargetService navigationTargets;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final com.mediassistrover.backend.ai.AiSensingService ai;
    private final com.mediassistrover.backend.validation.StepExecutionPolicy executionPolicy;

    public void apply(Job job,MqttWire.Envelope e) {
        if (job==null || !job.isBeOrchestrated() || job.getSchemaVersion()!=3 || job.getRobot()==null || !job.getRobot().getRobotId().equals(e.robotId()))
            throw new BusinessRuleException("MQTT event is outside orchestrated Job/Robot scope");
        UUID acknowledged="COMMAND_ACK".equals(e.messageType())?UUID.fromString(e.payload().path("commandId").asText()):e.correlationId();
        RobotCommandOutbox correlated=commands.lock(acknowledged).orElseThrow(()->new BusinessRuleException("No command for MQTT event"));
        boolean cancelAck="COMMAND_ACK".equals(e.messageType()) && "CANCEL_STEP".equals(correlated.getCommandType());
        boolean nullCancel=cancelAck && e.jobId()==null && e.jobStepId()==null;
        JobStep step=steps.lockById(correlated.getJobStepId()).orElseThrow(()->new BusinessRuleException("Unknown MQTT JobStep"));
        if (executionPolicy.isServer(step, step.getResolvedInputsJson()))
            throw new BusinessRuleException("Backend-owned step cannot accept MQTT runtime events");
        if (!correlated.getJobId().equals(job.getJobId()) || !correlated.getRobotId().equals(e.robotId())
                || !correlated.getCommandId().equals(e.correlationId()) || correlated.getSendAttempts()==0
                || !nullCancel && (!job.getJobId().equals(e.jobId()) || !step.getJobStepId().equals(e.jobStepId()) || !e.operationKey().equals(step.getOperationKey())))
            throw new BusinessRuleException("MQTT operation scope mismatch / event does not match a published command");
        RobotCommandOutbox command=commands.findByJobStepId(step.getJobStepId()).orElseThrow();command=commands.lock(command.getCommandId()).orElseThrow();
        boolean lateTerminal=Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED).contains(job.getStatus());
        if(lateTerminal && !cancelAck && !(step.getInterruptionReason()!=null && Set.of("STEP_ERROR","STEP_RESULT").contains(e.messageType())))
            throw new BusinessRuleException("New MQTT event arrived after Job terminal");
        boolean selected=job.getSelectedJobStep()!=null && job.getSelectedJobStep().getJobStepId().equals(step.getJobStepId());
        boolean stoppedVisit=step.getInterruptionReason()!=null && Boolean.TRUE.equals(command.getPhysicalStopConfirmed())
                && Set.of(JobStepStatus.SUCCEEDED,JobStepStatus.FAILED,JobStepStatus.TIMED_OUT,JobStepStatus.CANCELLED).contains(step.getStatus());
        if(!selected && !(stoppedVisit && (cancelAck || Set.of("STEP_ERROR","STEP_RESULT").contains(e.messageType()))))throw new BusinessRuleException("Event is not for the selected graph visit");
        JsonNode p=e.payload();Instant now=Instant.now(clock);String previous=step.getStatus().name();
        if(Set.of("STEP_RESULT","STEP_ERROR").contains(e.messageType()) && p.has("stepType")
                && !step.getStepTypeSnapshot().equals(p.path("stepType").asText()))
            throw new BusinessRuleException(e.messageType()+" StepType does not match the dispatched JobStep");
        switch (e.messageType()) {
            case "COMMAND_ACK" -> {
                if(cancelAck) {cancelAck(job,step,command,correlated,p,e,lateTerminal);break;}
                if (!command.getCommandId().equals(UUID.fromString(p.path("commandId").asText())) || !"EXECUTE_STEP".equals(p.path("commandType").asText())) throw new BusinessRuleException("COMMAND_ACK mismatch");
                if (!p.path("accepted").asBoolean()) {
                    if (Set.of("ACKNOWLEDGED","STARTED","COMPLETED").contains(command.getState())) throw new BusinessRuleException("Rejected ACK contradicts previous acceptance");
                    command.setState("REJECTED");step.setStatus(JobStepStatus.FAILED);step.setEndedAt(now);step.setErrorCode(p.path("errorCode").asText("COMMAND_REJECTED"));step.setErrorDetailsJson(p.deepCopy());
                    if (physical(step) && !p.path("physicalStopConfirmed").asBoolean()) safety.recovery(job,"Rejected physical command without stop evidence");
                    wakeups.request(job);
                } else {
                    if (Set.of("REJECTED","EXPIRED","RECOVERY_REQUIRED").contains(command.getState())) throw new BusinessRuleException("ACK after rejected/expired command");
                    if (!Set.of("STARTED","COMPLETED").contains(command.getState())) command.setState("ACKNOWLEDGED");command.setAcknowledgedAt(now);
                    if (p.path("replayed").asBoolean()) {
                        JsonNode snapshot=p.path("result");String status=snapshot.path("status").asText(p.path("status").asText());
                        if ("SUCCEEDED".equals(status) && snapshot.path("result").isObject()) result(job,step,command,snapshot,e.time());
                        else if (Set.of("FAILED","TIMED_OUT","CANCELLED","RECOVERY_REQUIRED").contains(status)) error(job,step,command,snapshot,status);
                        else if (!Set.of("ACCEPTED","RUNNING").contains(status)) safety.recovery(job,"Replay ACK has no usable journal state");
                    }
                }
            }
            case "STEP_STARTED" -> {
                if(step.getInterruptionReason()!=null || job.getCancelRequestedAt()!=null && !step.isMappingCleanup())throw new BusinessRuleException("STEP_STARTED after cancellation intent");
                if (job.getStatus()!=JobStatus.RUNNING || step.getStatus()!=JobStepStatus.PENDING || !"ACKNOWLEDGED".equals(command.getState())) throw new BusinessRuleException("STEP_STARTED requires accepted command and PENDING selected step");
                if (!step.getStepTypeSnapshot().equals(p.path("stepType").asText()) || !command.getPayloadJson().path("step").path("params").equals(p.path("resolvedInputs")))
                    throw new BusinessRuleException("STEP_STARTED differs from frozen command params");
                step.setStatus(JobStepStatus.RUNNING);step.setStartedAt(now);step.setAttemptNo(1);command.setState("STARTED");
            }
            case "STEP_RESULT" -> {if(!lateTerminal && selected)control.handle(job,step);result(job,step,command,p,e.time());remember(command,e);}
            case "STEP_ERROR" -> {if(!lateTerminal && selected)control.handle(job,step);error(job,step,command,p,p.path("status").asText());remember(command,e);}
            case "OPERATOR_RECONCILED" -> {command.setResultEnvelopeJson(wireNode(e));command.setState("RECOVERY_REQUIRED");safety.recovery(job,"Pi operator reconciliation requires BE staff review");}
            default -> throw new BusinessRuleException("Unsupported durable runtime event");
        }
        events.save(JobEvent.builder().jobEventId(e.messageId()).job(job).robot(job.getRobot()).jobTask(step.getJobTask()).jobStep(step)
                .producerType(JobEventProducerType.ROBOT).producerSessionId(e.producerSessionId()).sequenceNo(e.sequenceNo()).eventType(e.messageType())
                .correlationKey(e.correlationId().toString()).attemptNo(1).previousStatus(previous).newStatus(step.getStatus().name()).statusScope("JOB_STEP")
                .eventDataJson(p.deepCopy()).occurredAt(e.time()).receivedAt(now).persistedAt(now).build());
        updates.publishEvent(new com.mediassistrover.backend.service.RuntimeChanged(job.getJobId()));
    }
    private void result(Job job,JobStep step,RobotCommandOutbox command,JsonNode p,Instant reportedAt) {
        if(step.getInterruptionReason()!=null && Set.of(JobStepStatus.SUCCEEDED,JobStepStatus.FAILED,JobStepStatus.TIMED_OUT,JobStepStatus.CANCELLED).contains(step.getStatus())) {
            if(!"SUCCEEDED".equals(p.path("status").asText()) || step.getStatus()!=JobStepStatus.SUCCEEDED || !p.path("physicalStopConfirmed").asBoolean())
                throw new BusinessRuleException("Late result contradicts cancellation completion");
            outputs.validate(step,p.path("result"),reportedAt);return;
        }
        if (step.getStatus()!=JobStepStatus.RUNNING || !"STARTED".equals(command.getState())) throw new BusinessRuleException("Result requires committed STEP_STARTED snapshot");
        if (!p.path("result").isObject()) throw new BusinessRuleException("Journal completion lacks step output");
        outputs.validate(step,p.path("result"),reportedAt);
        ai.completed(step,command,p.path("result"),reportedAt);
        applyBackendState(step,p.path("result"));
        step.setResultJson(p.path("result").deepCopy());step.setStatus(JobStepStatus.SUCCEEDED);step.setEndedAt(Instant.now(clock));command.setState("COMPLETED");
        command.setPhysicalStopConfirmed(p.path("physicalStopConfirmed").isBoolean()?p.path("physicalStopConfirmed").asBoolean():null);
        if(com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(step) && p.path("physicalStopConfirmed").asBoolean())mapping.completed(step,p.path("result"),true);
        if (physical(step) && !p.path("physicalStopConfirmed").asBoolean()) {
            command.setInterruptionReason("PHYSICAL_STOP_UNCONFIRMED");
            safety.recovery(job,"Successful physical action has no confirmed stop");
        }
        if("MOVE".equals(step.getStepTypeSnapshot()) && job.getStatus()==JobStatus.RUNNING && step.getInterruptionReason()==null
                && p.path("physicalStopConfirmed").asBoolean() && step.getResultJson().path("reached").asBoolean()) navigationTargets.reached(step);
        finishInterrupted(job,step,command,p);
    }
    private void error(Job job,JobStep step,RobotCommandOutbox command,JsonNode p,String status) {
        if (!Set.of(JobStepStatus.PENDING,JobStepStatus.RUNNING).contains(step.getStatus())) {
            if(job.getStatus()==JobStatus.RECOVERY_REQUIRED && Set.of("FAILED","TIMED_OUT","CANCELLED").contains(status) && p.path("physicalStopConfirmed").asBoolean()) {
                // New stop evidence can support staff review, without reversing an earlier recovery/terminal decision.
                command.setPhysicalStopConfirmed(true);command.setState("COMPLETED");return;
            }
            if(step.getInterruptionReason()!=null && step.getStatus().name().equals(status) && Boolean.TRUE.equals(command.getPhysicalStopConfirmed()) && p.path("physicalStopConfirmed").asBoolean())return;
            throw new BusinessRuleException("Error contradicts existing terminal step");
        }
        JobStepStatus next;try {next=JobStepStatus.valueOf(status);} catch (IllegalArgumentException ex) {throw new BusinessRuleException("Invalid journal error status");}
        if (!Set.of(JobStepStatus.FAILED,JobStepStatus.TIMED_OUT,JobStepStatus.CANCELLED,JobStepStatus.RECOVERY_REQUIRED).contains(next)) throw new BusinessRuleException("Invalid error status");
        step.setStatus(next);step.setEndedAt(Instant.now(clock));step.setErrorCode(p.path("errorCode").isNull()?null:p.path("errorCode").asText());step.setErrorDetailsJson(p.deepCopy());
        if (p.path("result").isObject()) step.setResultJson(p.path("result").deepCopy());
        command.setState(next==JobStepStatus.RECOVERY_REQUIRED?"RECOVERY_REQUIRED":"COMPLETED");
        command.setPhysicalStopConfirmed(p.path("physicalStopConfirmed").isBoolean()?p.path("physicalStopConfirmed").asBoolean():null);
        if (next==JobStepStatus.RECOVERY_REQUIRED || physical(step) && !p.path("physicalStopConfirmed").asBoolean()) {
            command.setInterruptionReason("PHYSICAL_STOP_UNCONFIRMED");
            safety.recovery(job,"Pi error/restart lacks confirmed physical stop");
        }
        else if(step.getInterruptionReason()!=null)finishInterrupted(job,step,command,p);
        else if (next==JobStepStatus.CANCELLED && job.getStatus()==JobStatus.RUNNING) {
            if(mapping.open(job.getJobId())) {job.setCancelRequestedAt(Instant.now(clock));wakeups.request(job);return;}
            Instant now=Instant.now(clock);job.setStatus(JobStatus.CANCELLED);job.setEndedAt(now);
            if (job.getServiceRequest()!=null) {job.getServiceRequest().setStatus(ServiceRequestStatus.CANCELLED);job.getServiceRequest().setCompletedAt(now);}
            if (job.getRobot().getStatus()==RobotStatus.BUSY) job.getRobot().setStatus(RobotStatus.IDLE);
            for (JobTask task:tasks.findByJobJobIdOrderBySequenceNoAsc(job.getJobId())) if (task.getStatus()==JobTaskStatus.RUNNING) {task.setStatus(JobTaskStatus.CANCELLED);task.setEndedAt(now);}
            wakeups.request(job);
        } else if (job.getStatus()==JobStatus.RUNNING) wakeups.request(job);
    }
    private void finishInterrupted(Job job,JobStep step,RobotCommandOutbox command,JsonNode payload) {
        if(step.getInterruptionReason()!=null) {
            if(payload.path("physicalStopConfirmed").asBoolean())control.completeStopped(job,step,command,step.getInterruptionReason());
            else safety.recovery(job,"Interrupted operation completed without confirmed stop");
        } else if(job.getStatus()==JobStatus.RUNNING)wakeups.request(job);
    }
    private void cancelAck(Job job,JobStep step,RobotCommandOutbox execute,RobotCommandOutbox cancel,JsonNode p,MqttWire.Envelope e,boolean lateTerminal) {
        if(!"CANCEL_STEP".equals(p.path("commandType").asText()))throw new BusinessRuleException("Cancel COMMAND_ACK type mismatch");
        if("RESOLVED_BY_STAFF".equals(cancel.getState()))return;
        remember(cancel,e);cancel.setAcknowledgedAt(Instant.now(clock));
        if(!p.path("accepted").asBoolean()) {
            if("STOP_CONFIRMED".equals(cancel.getState()))return; // A late rejection cannot undo committed stop evidence.
            cancel.setState("REJECTED");safety.recovery(job,"Pi rejected cancellation");return;
        }
        String status=p.path("result").path("status").asText(p.path("status").asText());
        boolean stopped=p.path("physicalStopConfirmed").asBoolean() || p.path("result").path("physicalStopConfirmed").asBoolean();
        if(stopped && Set.of("CANCELLED","FAILED","TIMED_OUT","SUCCEEDED").contains(status)) {
            cancel.setPhysicalStopConfirmed(true);cancel.setState("STOP_CONFIRMED");execute.setPhysicalStopConfirmed(true);
            if(!Set.of(JobStepStatus.SUCCEEDED,JobStepStatus.FAILED,JobStepStatus.TIMED_OUT,JobStepStatus.CANCELLED,JobStepStatus.RECOVERY_REQUIRED).contains(step.getStatus())) {
                if("SUCCEEDED".equals(status)) {
                    if(step.getStatus()!=JobStepStatus.RUNNING || !"STARTED".equals(execute.getState())) {safety.recovery(job,"Succeeded cancel journal lacks committed STEP_STARTED");return;}
                    JsonNode output=p.path("result").path("result");
                    if(!output.isObject()) {safety.recovery(job,"Cancel journal succeeded but successful output is missing");return;}
                    outputs.validate(step,output,e.time());ai.completed(step,execute,output,e.time());mapping.completed(step,output,true);step.setResultJson(output.deepCopy());
                }
                step.setStatus(JobStepStatus.valueOf(status));step.setEndedAt(Instant.now(clock));
                step.setErrorCode("BE_"+step.getInterruptionReason());step.setErrorDetailsJson(p.deepCopy());
            }
            if(!lateTerminal)control.completeStopped(job,step,execute,step.getInterruptionReason());
        } else if("STOP_CONFIRMED".equals(cancel.getState()))return;
        else if(Set.of("CANCELLED","FAILED","TIMED_OUT","SUCCEEDED","RECOVERY_REQUIRED").contains(status)) {
            cancel.setState("RECOVERY_REQUIRED");safety.recovery(job,"Cancel ACK has terminal state without confirmed stop");
        } else cancel.setState("ACKNOWLEDGED");
    }
    private void remember(RobotCommandOutbox command,MqttWire.Envelope e) {command.setResultEnvelopeJson(wireNode(e));}
    private JsonNode wireNode(MqttWire.Envelope e) {
        return wire.tree(e);
    }
    private void applyBackendState(JobStep step,JsonNode result) {
        if(!"SET_CONTEXT".equals(step.getStepTypeSnapshot()))return;
        Job job=step.getJobTask().getJob();
        com.fasterxml.jackson.databind.node.ObjectNode context=job.getContextJson()!=null && job.getContextJson().isObject()
                ?((com.fasterxml.jackson.databind.node.ObjectNode)job.getContextJson()).deepCopy():wire.object();
        result.path("updated").fields().forEachRemaining(entry->context.set(entry.getKey(),entry.getValue().deepCopy()));
        job.setContextJson(context);
    }
    private boolean physical(JobStep step) {return Set.of("MOVE","CONTROL_HARDWARE").contains(step.getStepTypeSnapshot()) || com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(step);}
}
