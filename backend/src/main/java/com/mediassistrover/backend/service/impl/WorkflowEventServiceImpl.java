package com.mediassistrover.backend.service.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.dto.job.response.WorkflowEventResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.RuntimeAuthorizationPolicy;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class WorkflowEventServiceImpl implements WorkflowEventService {
    private final JobRepository jobRepository; private final JobEventRepository eventRepository; private final JobStepRepository stepRepository;
    private final AccountRepository accountRepository; private final RuntimeStepCompletionService completionService; private final AuditService auditService;
    private final ObjectMapper objectMapper; private final Clock clock;
    private final OrchestrationWorkService wakeups;
    private final org.springframework.context.ApplicationEventPublisher updates;
    private final MappingSessionRepository mappingSessions;
    private final StaffOperationalScopePolicy staffScope;
    @Override @Transactional public WorkflowEventResponse publish(UUID jobId,WorkflowEventRequest r,AccountPrincipal actor,RequestMetadata md){
        Job job=jobRepository.lockById(jobId).orElseThrow(()->new ResourceNotFoundException("Job not found: "+jobId));staffScope.requireJobAccess(job,actor);
        JobEvent duplicate=eventRepository.findById(r.eventId()).orElse(null);if(duplicate!=null){JsonNode requestedData=r.data()==null?objectMapper.createObjectNode():r.data();if(!duplicate.getJob().getJobId().equals(jobId)||!Objects.equals(duplicate.getActorAccount()==null?null:duplicate.getActorAccount().getAccountId(),actor.accountId())||!duplicate.getEventType().equals(r.eventType())||!Objects.equals(duplicate.getCorrelationKey(),r.correlationKey())||!sameInstant(duplicate.getOccurredAt(),r.occurredAt())||!Objects.equals(duplicate.getEventDataJson(),requestedData))throw new ConflictException("eventId was reused with another event payload");return response(duplicate,true);}
        if(job.isBeOrchestrated() && "ALERT_ACKNOWLEDGED".equals(r.eventType()))throw new BusinessRuleException("ALERT_ACKNOWLEDGED is generated only by recipient ACK policy");
        if("MAPPING_SAVE_REQUESTED".equals(r.eventType())) {
            var session=mappingSessions.findByJobId(jobId).orElseThrow(()->new BusinessRuleException("Job has no BE mapping session"));
            if(!session.getSessionId().toString().equals(r.correlationKey()) || !session.isPotentiallyOpen()
                    || !"MAPPING".equals(session.getState()) || job.getCancelRequestedAt()!=null)throw new BusinessRuleException("Mapping SAVE event requires the active session and correct correlation key");
        }
        RuntimeAuthorizationPolicy.requireRunning(job);if(stepRepository.findByJobId(jobId).stream().noneMatch(wait->matches(wait,r)))throw new BusinessRuleException("Event type and correlation are not registered by this Job workflow");
        Instant now=Instant.now(clock);if(r.occurredAt().isAfter(now.plusSeconds(300)))throw new BusinessRuleException("occurredAt is too far in the future");if(job.getStartedAt()!=null&&r.occurredAt().isBefore(job.getStartedAt()))throw new BusinessRuleException("Event predates this Job run");Account account=accountRepository.findById(actor.accountId()).orElseThrow();
        JobEvent event=eventRepository.saveAndFlush(JobEvent.builder().jobEventId(r.eventId()).job(job).robot(job.getRobot()).actorAccount(account).producerType(actor.role()==Role.ADMIN?JobEventProducerType.ADMIN:JobEventProducerType.STAFF).producerSessionId(r.eventId()).sequenceNo(1).eventType(r.eventType()).correlationKey(r.correlationKey()).statusScope("WORKFLOW_EVENT").eventDataJson(r.data()==null?objectMapper.createObjectNode():r.data().deepCopy()).occurredAt(r.occurredAt()).receivedAt(now).persistedAt(now).build());
        for(JobStep wait:stepRepository.lockRunningWaits(jobId))if(consumeAvailable(wait))break;
        auditService.record(account,"WORKFLOW_EVENT_PUBLISHED","Job",jobId,null,Map.of("eventId",r.eventId(),"eventType",r.eventType()),md);return response(event,false);
    }
    @Override @Transactional
    public void publishAlertAcknowledged(Alert alert,Account actor,Instant occurredAt,long acknowledged,long total) {
        if(!alert.isRequireAck() || alert.getStatus()!=AlertStatus.ACKNOWLEDGED || total<1 || acknowledged<1
                || alert.getAckPolicy()==AlertAckPolicy.ALL && acknowledged<total)throw new BusinessRuleException("Alert ACK policy is not satisfied");
        if(alert.getJobStep()==null)return;
        Job job=jobRepository.lockById(alert.getJobStep().getJobTask().getJob().getJobId()).orElseThrow();
        if(!job.isBeOrchestrated() || job.getSchemaVersion()!=3 || job.getStatus()!=JobStatus.RUNNING || job.getCancelRequestedAt()!=null)return;
        UUID id=UUID.nameUUIDFromBytes(("ALERT_ACKNOWLEDGED:"+alert.getAlertId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(eventRepository.existsById(id))return;
        ObjectNode data=objectMapper.createObjectNode().put("alert_id",alert.getAlertId().toString()).put("ack_policy",alert.getAckPolicy().name())
                .put("acknowledged_recipients",acknowledged).put("total_recipients",total).put("actor_account_id",actor.getAccountId().toString());
        Instant now=Instant.now(clock);
        eventRepository.saveAndFlush(JobEvent.builder().jobEventId(id).job(job).robot(job.getRobot()).actorAccount(actor).producerType(JobEventProducerType.SERVER)
                .producerSessionId(id).sequenceNo(1).eventType("ALERT_ACKNOWLEDGED").correlationKey(alert.getAlertId().toString()).statusScope("WORKFLOW_EVENT")
                .eventDataJson(data).occurredAt(occurredAt).receivedAt(now).persistedAt(now).build());
        // Job lock is already held by Alert ACK before it acquired Alert/Recipient locks.
        JobStep selected=job.getSelectedJobStep();
        if(selected!=null && selected.getStatus()==JobStepStatus.RUNNING && "WAIT".equals(selected.getStepTypeSnapshot())
                && selected.getStartedAt().plusSeconds(selected.getTimeoutSecSnapshot()).isAfter(now)
                && job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(now))consumeAvailable(selected);
        wakeups.request(job);updates.publishEvent(new RuntimeChanged(job.getJobId()));
    }
    @Override public boolean consumeAvailable(JobStep wait){
        JsonNode input=wait.getResolvedInputsJson();if(input==null||!"EVENT".equals(input.path("wait_type").asText()))return false;String type=input.path("event_type").asText();String correlation=input.path("correlation_key").asText(wait.getOperationKey());Job job=wait.getJobTask().getJob();Instant notBefore=job.getStartedAt()==null?job.getQueuedAt():job.getStartedAt();List<JobEvent> matches=eventRepository.lockUnconsumed(job.getJobId(),type,correlation,notBefore);if(matches.isEmpty())return false;JobEvent event=matches.getFirst();Instant now=Instant.now(clock);event.setConsumedByJobStep(wait);event.setConsumedAt(now);
        ObjectNode eventJson=objectMapper.createObjectNode().put("event_id",event.getJobEventId().toString()).put("type",event.getEventType()).put("correlation_key",event.getCorrelationKey()).put("job_id",job.getJobId().toString()).put("actor_user_id",event.getActorAccount()==null?null:event.getActorAccount().getAccountId().toString()).put("occurred_at",event.getOccurredAt().toString());if(job.getSchemaVersion()==3){eventJson=objectMapper.createObjectNode().put("event_type",event.getEventType()).put("correlation_key",event.getCorrelationKey()).put("occurred_at",event.getOccurredAt().toString());eventJson.set("data",event.getEventDataJson().deepCopy());}ObjectNode result=objectMapper.createObjectNode().put("completed",true).put("wake_reason","EVENT").put("elapsed_sec",Math.max(0,Duration.between(wait.getStartedAt(),now).toMillis()/1000.0));result.set("event",eventJson);
        return completionService.complete(wait,result,event.getActorAccount(),event.getProducerType(),"WAIT_EVENT_CONSUMED",null);
    }
    private WorkflowEventResponse response(JobEvent e,boolean duplicate){return new WorkflowEventResponse(e.getJobEventId(),e.getJob().getJobId(),e.getEventType(),e.getCorrelationKey(),duplicate,e.getConsumedByJobStep()==null?null:e.getConsumedByJobStep().getJobStepId(),e.getConsumedAt());}
    private boolean matches(JobStep wait,WorkflowEventRequest request){if(!"WAIT".equals(wait.getStepTypeSnapshot()))return false;JsonNode input=wait.getResolvedInputsJson();if(input==null||!"EVENT".equals(input.path("wait_type").asText()))input=wait.getStep().getParamsJson();if(input==null||!"EVENT".equals(input.path("wait_type").asText()))return false;String correlation=input.path("correlation_key").asText(wait.getOperationKey());return request.eventType().equals(input.path("event_type").asText())&&request.correlationKey().equals(correlation);}
    private boolean sameInstant(Instant left,Instant right){return left!=null&&right!=null&&left.truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(right.truncatedTo(java.time.temporal.ChronoUnit.MICROS));}
}
