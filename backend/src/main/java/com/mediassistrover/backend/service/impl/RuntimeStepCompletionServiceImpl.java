package com.mediassistrover.backend.service.impl;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.RuntimeStepCompletionService;
import com.mediassistrover.backend.validation.StepResultContractValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class RuntimeStepCompletionServiceImpl implements RuntimeStepCompletionService {
    private final JobRepository jobRepository; private final com.mediassistrover.backend.service.OrchestrationWorkService wakeups;
    private final StepResultContractValidator validator; private final JobStepRepository stepRepository;
    private final JobEventRepository eventRepository; private final Clock clock;
    private final com.mediassistrover.backend.validation.StepExecutionPolicy executionPolicy;
    @Override @org.springframework.transaction.annotation.Transactional public boolean complete(JobStep supplied,JsonNode result,Account actor,JobEventProducerType producer,String eventType,UUID eventId){
        Job job=jobRepository.lockById(supplied.getJobTask().getJob().getJobId()).orElseThrow();
        if(job.isBeOrchestrated() && (job.getStatus()!=JobStatus.RUNNING || job.getCancelRequestedAt()!=null
                || job.getSelectedJobStep()==null || !job.getSelectedJobStep().getJobStepId().equals(supplied.getJobStepId())))
            throw new com.mediassistrover.backend.exception.BusinessRuleException("Completion requires the selected running JobStep");
        JobStep step=stepRepository.lockById(supplied.getJobStepId()).orElseThrow();
        if (job.isBeOrchestrated() && !executionPolicy.isServer(step, step.getResolvedInputsJson()))
            throw new com.mediassistrover.backend.exception.BusinessRuleException(
                    "MQTT-owned JobStep cannot be completed by a backend executor");
        if(step.getStatus()==JobStepStatus.SUCCEEDED)return false;
        if(step.getStatus()!=JobStepStatus.RUNNING)return false;
        Instant now=Instant.now(clock);
        if(job.isBeOrchestrated() && (!step.getStartedAt().plusSeconds(step.getTimeoutSecSnapshot()).isAfter(now)
                || !job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(now)))
            throw new com.mediassistrover.backend.exception.BusinessRuleException("JobStep response arrived after its deadline");
        validator.validate(step,result);
        step.setResultJson(result.deepCopy());step.setStatus(JobStepStatus.SUCCEEDED);step.setEndedAt(now);
        JobTask task=step.getJobTask();List<JobStep> taskSteps=stepRepository.findByJobId(task.getJob().getJobId()).stream().filter(s->s.getJobTask().getJobTaskId().equals(task.getJobTaskId())).toList();
        if(!job.isBeOrchestrated() && taskSteps.stream().allMatch(s->s.getJobStepId().equals(step.getJobStepId())||s.getStatus()==JobStepStatus.SUCCEEDED)){task.setStatus(JobTaskStatus.SUCCEEDED);task.setEndedAt(now);}
        eventRepository.save(JobEvent.builder().jobEventId(eventId==null?UUID.randomUUID():eventId).job(task.getJob()).robot(task.getJob().getRobot()).jobTask(task).jobStep(step).actorAccount(actor).producerType(producer).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType(eventType).previousStatus(JobStepStatus.RUNNING.name()).newStatus(JobStepStatus.SUCCEEDED.name()).statusScope("JOB_STEP").eventDataJson(result.deepCopy()).occurredAt(now).receivedAt(now).persistedAt(now).build());
        wakeups.request(job);
        return true;
    }
}
