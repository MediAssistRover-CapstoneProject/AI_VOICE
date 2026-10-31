package com.mediassistrover.backend.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OperationsMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service
public class OperationsMaintenanceServiceImpl implements OperationsMaintenanceService {
    private final JobRepository jobRepository; private final JobStepRepository stepRepository;
    private final JobEventRepository eventRepository; private final RevokedTokenRepository revokedTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository; private final ObjectMapper objectMapper;
    private final com.mediassistrover.backend.service.RuntimeStepCompletionService completionService;
    @org.springframework.beans.factory.annotation.Autowired
    public OperationsMaintenanceServiceImpl(JobRepository jobs,JobStepRepository steps,JobEventRepository events,RevokedTokenRepository revoked,RefreshTokenRepository refresh,ObjectMapper mapper,com.mediassistrover.backend.service.RuntimeStepCompletionService completion){this.jobRepository=jobs;this.stepRepository=steps;this.eventRepository=events;this.revokedTokenRepository=revoked;this.refreshTokenRepository=refresh;this.objectMapper=mapper;this.completionService=completion;}
    public OperationsMaintenanceServiceImpl(JobRepository jobs,JobStepRepository steps,JobEventRepository events,RevokedTokenRepository revoked,RefreshTokenRepository refresh,ObjectMapper mapper){this(jobs,steps,events,revoked,refresh,mapper,null);}
    @Override @Transactional public int markOverdueJobs(Instant now){List<Job> jobs=jobRepository.lockOverdue(List.of(JobStatus.QUEUED),now);jobs.forEach(j->recovery(j,"DISPATCH_ACK_TIMEOUT",now));return jobs.size();}
    @Override @Transactional public int markTimedOutSteps(Instant now){List<JobStep> candidates=stepRepository.lockPotentialTimeouts(now.minusSeconds(1));int changed=0;for(JobStep s:candidates){if(s.getStatus()!=JobStepStatus.RUNNING)continue;
        // Schema 3 timeouts and WAIT(DURATION) belong to the orchestrator/MQTT lifecycle.
        // Never let this legacy scheduler race a robot result, even if a repository query regresses.
        if(s.getJobTask().getJob().isBeOrchestrated())continue;
        if(StepType.WAIT.name().equals(s.getStepTypeSnapshot())&&"DURATION".equals(s.getResolvedInputsJson().path("wait_type").asText())){double duration=s.getResolvedInputsJson().path("duration_sec").asDouble();long elapsedMillis=Duration.between(s.getStartedAt(),now).toMillis();if(elapsedMillis>=Math.ceil(duration*1000)){var result=objectMapper.createObjectNode().put("completed",true).put("wake_reason","DURATION").put("elapsed_sec",elapsedMillis/1000.0);result.putNull("event");if(completionService.complete(s,result,null,JobEventProducerType.SCHEDULER,"WAIT_DURATION_COMPLETED",null))changed++;continue;}}
        if(!s.getStartedAt().plusSeconds(s.getTimeoutSecSnapshot()).isAfter(now)){s.setStatus(JobStepStatus.TIMED_OUT);s.setEndedAt(now);s.setErrorCode("STEP_TIMEOUT");Job j=s.getJobTask().getJob();eventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(j).robot(j.getRobot()).jobTask(s.getJobTask()).jobStep(s).producerType(JobEventProducerType.SCHEDULER).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType("STEP_TIMEOUT").previousStatus(JobStepStatus.RUNNING.name()).newStatus(JobStepStatus.TIMED_OUT.name()).statusScope("JOB_STEP").eventDataJson(objectMapper.createObjectNode().put("reason","STEP_TIMEOUT")).occurredAt(now).receivedAt(now).persistedAt(now).build());changed++;}}return changed;}
    @Override @Transactional public long cleanupExpiredTokens(Instant now){return revokedTokenRepository.deleteByExpiresAtLessThanEqual(now)+refreshTokenRepository.deleteByExpiresAtLessThanEqual(now);}
    private void recovery(Job j,String code,Instant now){if(Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED,JobStatus.RECOVERY_REQUIRED).contains(j.getStatus()))return;String old=j.getStatus().name();j.setStatus(JobStatus.RECOVERY_REQUIRED);j.setErrorCode(code);j.setFailureReason("Manual reconciliation is required");if(j.getServiceRequest()!=null)j.getServiceRequest().setStatus(ServiceRequestStatus.RECOVERY_REQUIRED);if(j.getRobot()!=null)j.getRobot().setStatus(RobotStatus.ERROR);eventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(j).robot(j.getRobot()).producerType(JobEventProducerType.SCHEDULER).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType(code).previousStatus(old).newStatus(JobStatus.RECOVERY_REQUIRED.name()).statusScope("JOB").eventDataJson(objectMapper.createObjectNode().put("reason",code)).occurredAt(now).receivedAt(now).persistedAt(now).build());}
}
