package com.mediassistrover.backend.service.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.StepServiceExecutePayload;
import com.mediassistrover.backend.dto.pi.response.PiResponses.StepServiceExecutionResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.PiStepService;
import com.mediassistrover.backend.service.JobStepInputResolver;
import com.mediassistrover.backend.service.JobStepRuntimeGuard;
import com.mediassistrover.backend.service.ServerStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PiStepServiceImpl implements PiStepService {
    private final JobRepository jobRepository;
    private final com.mediassistrover.backend.mqtt.MqttRolloutService rollout;
    private final JobStepRepository stepRepository; private final JobEventRepository eventRepository;
    private final ObjectMapper objectMapper; private final Clock clock;
    private final JobStepInputResolver inputResolver;
    private final com.mediassistrover.backend.service.ServerStepExecutionService serverExecution;
    private final JobStepRuntimeGuard runtimeGuard;
    private final com.mediassistrover.backend.validation.StepResultContractValidator stepResultValidator;
    @Override @Transactional public StepServiceExecutionResponse execute(PiEnvelope<StepServiceExecutePayload> e,DevicePrincipal p){validateEnvelope(e,p);jobRepository.lockById(e.jobId()).orElseThrow(()->new ResourceNotFoundException("Job not found"));JobStep step=step(e.jobStepId());Job job=step.getJobTask().getJob();validateScope(e,p,step,job);if(eventRepository.existsById(e.messageId())||eventRepository.existsByProducerSessionIdAndSequenceNo(e.producerSessionId(),e.sequenceNo()))return response(e.messageId(),true,step);StepExecutorKind kind=step.getStep().getStepType().getExecutorKind(job.getSchemaVersion());if(kind!=StepExecutorKind.SERVER&&kind!=StepExecutorKind.MIXED)throw new BusinessRuleException("Step is not executable by Server");if(job.getStatus()!=JobStatus.RUNNING)throw new BusinessRuleException("Job must be RUNNING");if(step.getStatus()==JobStepStatus.SUCCEEDED)return response(e.messageId(),true,step);if(step.getStatus()!=JobStepStatus.PENDING)throw new BusinessRuleException("Server step cannot execute in "+step.getStatus());runtimeGuard.requireStartAllowed(step);Instant now=Instant.now(clock);step.setResolvedInputsJson(inputResolver.resolve(step));step.setStatus(JobStepStatus.RUNNING);step.setStartedAt(now);JobTask task=step.getJobTask();if(task.getStatus()==JobTaskStatus.PENDING){task.setStatus(JobTaskStatus.RUNNING);task.setStartedAt(now);}ServerStepHandler.ServerStepExecution execution=executeContract(step,e.payload().input());step.setResultJson(execution.result());if(execution.completed()){stepResultValidator.validate(step,execution.result());step.setStatus(JobStepStatus.SUCCEEDED);step.setEndedAt(now);}boolean completed=execution.completed()||step.getStatus()==JobStepStatus.SUCCEEDED;String eventType=completed?"SERVER_STEP_COMPLETED":"SERVER_STEP_DISPATCHED";eventRepository.save(JobEvent.builder().jobEventId(e.messageId()).job(job).robot(job.getRobot()).jobTask(task).jobStep(step).producerType(JobEventProducerType.ROBOT).producerSessionId(e.producerSessionId()).sequenceNo(e.sequenceNo()).eventType(eventType).correlationKey(e.correlationId().toString()).attemptNo(step.getAttemptNo()).previousStatus(JobStepStatus.PENDING.name()).newStatus(step.getStatus().name()).statusScope("JOB_STEP").eventDataJson(execution.result()).occurredAt(e.occurredAt()).receivedAt(now).persistedAt(now).build());return response(e.messageId(),false,step);}
    @Override public StepServiceExecutionResponse result(UUID id,DevicePrincipal p){rollout.requireLegacyExecution();JobStep step=step(id);Job job=step.getJobTask().getJob();if(job.getRobot()==null||!job.getRobot().getRobotId().equals(p.robotId()))throw new org.springframework.security.access.AccessDeniedException("JobStep is not assigned to this robot");return response(null,false,step);}
    private ServerStepHandler.ServerStepExecution executeContract(JobStep step,JsonNode input){return serverExecution.executePrepared(step);}
    private void validateEnvelope(PiEnvelope<?> e,DevicePrincipal p){rollout.requireLegacyExecution();if(!"1.0".equals(e.protocolVersion())||!"STEP_SERVICE_EXECUTE".equals(e.messageType()))throw new BusinessRuleException("Invalid protocol or messageType");if(!p.robotId().equals(e.robotId()))throw new org.springframework.security.access.AccessDeniedException("Envelope robotId does not match device token");if(e.jobId()==null||e.jobStepId()==null)throw new BusinessRuleException("jobId and jobStepId are required");if(e.occurredAt().isAfter(Instant.now(clock).plusSeconds(300)))throw new BusinessRuleException("occurredAt is too far in the future");}
    private void validateScope(PiEnvelope<?> e,DevicePrincipal p,JobStep step,Job job){if(job.isBeOrchestrated())throw new BusinessRuleException("BE orchestrated Jobs cannot use legacy step-service execution");if(!job.getJobId().equals(e.jobId())||job.getRobot()==null||!job.getRobot().getRobotId().equals(p.robotId()))throw new org.springframework.security.access.AccessDeniedException("JobStep does not belong to envelope Job/Robot");if(!step.getOperationKey().equals(e.operationKey()))throw new BusinessRuleException("operationKey does not match JobStep");}
    private StepServiceExecutionResponse response(UUID messageId,boolean duplicate,JobStep step){return new StepServiceExecutionResponse(messageId,duplicate,step.getJobStepId(),step.getStatus(),step.getResultJson(),Instant.now(clock));}
    private JobStep step(UUID id){return stepRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("JobStep not found: "+id));}
}
