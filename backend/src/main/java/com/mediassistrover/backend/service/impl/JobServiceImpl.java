package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.job.request.JobRequests.*;
import com.mediassistrover.backend.dto.job.response.JobResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.RequestJobMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class JobServiceImpl implements JobService {
    private final JobRepository jobRepository; private final JobTaskRepository taskRepository;
    private final JobStepRepository stepRepository; private final JobEventRepository eventRepository;
    private final RobotRepository robotRepository; private final AccountRepository accountRepository;
    private final RequestJobMapper mapper; private final AuditService auditService;
    private final OrchestrationWorkService wakeups;
    private final ObjectMapper objectMapper; private final Clock clock;
    private final com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;
    private final RobotCommandOutboxRepository mqttCommands;
    private final StaffOperationalScopePolicy staffScope;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final com.mediassistrover.backend.mqtt.MqttSessionRegistry mqttSessions;
    private final RobotMqttSessionRepository robotSessions;

    private void reconcileStoppedMappingWait(Job job,JobStatus terminal) {
        if(!mapping.open(job.getJobId()))return;
        JobStep selected=job.getSelectedJobStep();
        if(selected==null || !"WAIT".equals(selected.getStepTypeSnapshot())
                || !"MAPPING_SAVE_REQUESTED".equals(selected.getResolvedInputsJson().path("event_type").asText()))
            throw new BusinessRuleException("Active Pi mapping operations require operation recovery");
        var session=robotSessions.findById(job.getRobot().getRobotId()).orElse(null);
        Instant now=Instant.now(clock);
        if(!mqttSessions.owned(session) || !session.isHandshakeAcked() || session.getLastHeartbeatAt()==null
                || !session.getLastHeartbeatAt().isAfter(now.minusSeconds(15))
                || session.getLastHeartbeatAt().isBefore(session.getHelloReceivedAt()))
            throw new BusinessRuleException("Mapping reconciliation requires a fresh HELLO and heartbeat");
        var heartbeat=session.getHeartbeatJson();
        if(heartbeat==null || !"IDLE".equals(heartbeat.path("status").asText())
                || !heartbeat.path("activeJobStepId").isNull()
                || com.mediassistrover.backend.mapping.MappingLifecycleService.reportsOpen(heartbeat.path("mappingSession"))
                || com.mediassistrover.backend.mapping.MappingLifecycleService.reportsOpen(session.getHelloJson().path("mappingSession")))
            throw new BusinessRuleException("Stop and close the Pi mapping session before reconciliation");
        var odom=heartbeat.path("telemetry").path("odom_speed");
        if(!odom.path("value").isNumber() || odom.path("value").asDouble()<0 || odom.path("value").asDouble()>.01
                || !odom.path("age_sec").isNumber() || odom.path("age_sec").asDouble()<0 || odom.path("age_sec").asDouble()>.5)
            throw new BusinessRuleException("Mapping reconciliation requires fresh stationary odometry");
        for(var command:mqttCommands.findByJobIdOrderByCreatedAt(job.getJobId()))
            if(command.getSendAttempts()>0 && (!"COMPLETED".equals(command.getState()) || !Boolean.TRUE.equals(command.getPhysicalStopConfirmed())))
                throw new BusinessRuleException("Mapping Pi command still requires operation/stop reconciliation");
        for(var state:session.getHelloJson().path("states"))
            if(Set.of("ACCEPTED","RUNNING","RECOVERY_REQUIRED").contains(state.path("status").asText()))
                throw new BusinessRuleException("Pi journal still has an unfinished operation");
        mapping.reconciled(job);
        selected.setStatus(terminal==JobStatus.CANCELLED?JobStepStatus.CANCELLED:JobStepStatus.FAILED);selected.setEndedAt(now);
        for(JobTask task:taskRepository.findByJobJobIdOrderBySequenceNoAsc(job.getJobId()))
            if(task.getStartedAt()!=null && task.getEndedAt()==null){task.setStatus(terminal==JobStatus.CANCELLED?JobTaskStatus.CANCELLED:JobTaskStatus.FAILED);task.setEndedAt(now);}
    }
    @Override public Page<JobResponse> list(JobStatus status,UUID workflowId,UUID requestId,UUID robotId,Instant from,Instant to,AccountPrincipal a,Pageable p){validateWindow(from,to);staffScope.requireCurrentStaffOrAdmin(a);if(a.role()==Role.ADMIN)return jobRepository.search(status,workflowId,requestId,robotId,from,to,p).map(this::safeJob);List<Job> scoped=jobRepository.search(status,workflowId,requestId,robotId,from,to,Pageable.unpaged(p.getSort())).getContent().stream().filter(j->staffScope.canAccessJob(j,a)).toList();return page(scoped,p).map(this::safeJob);}
    @Override public JobDetailResponse get(UUID id,AccountPrincipal a){Job j=job(id);scope(j,a);return new JobDetailResponse(safeJob(j),taskRepository.findByJobJobIdOrderBySequenceNoAsc(id).stream().map(mapper::toResponse).toList(),stepRepository.findByJobId(id).stream().map(this::safeStep).toList());}
    @Override public Page<JobEventResponse> events(UUID id,AccountPrincipal a,Pageable p){scope(job(id),a);return eventRepository.findByJobJobId(id,p).map(e->safeEvent(e,a));}
    @Override @Transactional public JobResponse cancel(UUID id,CancelJobRequest r,AccountPrincipal a,RequestMetadata md){Job j=jobRepository.lockById(id).orElseThrow(()->new ResourceNotFoundException("Job not found"));scope(j,a);if(terminal(j.getStatus()))throw new BusinessRuleException("Job is already terminal");if(j.getStatus()==JobStatus.RECOVERY_REQUIRED)throw new BusinessRuleException("Reconcile RECOVERY_REQUIRED job instead");if(j.getCancelCommandId()==null){j.setCancelCommandId(UUID.randomUUID());j.setCancelRequestedAt(Instant.now(clock));j.setCancelRequestedByAccount(account(a.accountId()));event(j,a,"CANCEL_REQUESTED",j.getStatus().name(),j.getStatus().name(),objectMapper.createObjectNode().put("reason",r.reason()));}wakeups.request(j);audit(a,"JOB_CANCEL_REQUESTED",j,md);return mapper.toResponse(j);}
    @Override @Transactional public JobResponse reconcile(UUID id,ReconcileJobRequest r,AccountPrincipal a,RequestMetadata md){Job j=jobRepository.lockById(id).orElseThrow(()->new ResourceNotFoundException("Job not found"));scope(j,a);if(j.getStatus()!=JobStatus.RECOVERY_REQUIRED)throw new BusinessRuleException("Only RECOVERY_REQUIRED job can be reconciled");if(j.isBeOrchestrated() && mqttCommands.existsUnreconciledByJobId(id))throw new BusinessRuleException("MQTT recovery requires operation/stop reconciliation via the operation recovery API; generic Job reconciliation cannot release this robot");JobStatus next=r.outcome()==ReconcileOutcome.CONFIRM_CANCELLED?JobStatus.CANCELLED:JobStatus.FAILED;reconcileStoppedMappingWait(j,next);j.setStatus(next);j.setReconciledByAccount(account(a.accountId()));j.setReconciledAt(Instant.now(clock));j.setReconciliationNote(r.note());j.setEndedAt(Instant.now(clock));if(j.getServiceRequest()!=null){j.getServiceRequest().setStatus(next==JobStatus.CANCELLED?ServiceRequestStatus.CANCELLED:ServiceRequestStatus.FAILED);j.getServiceRequest().setCompletedAt(Instant.now(clock));}if(j.getRobot()!=null&&Set.of(RobotStatus.BUSY,RobotStatus.ERROR).contains(j.getRobot().getStatus()))j.getRobot().setStatus(RobotStatus.STARTING);requireNewMqttHello(j);event(j,a,"JOB_RECONCILED",JobStatus.RECOVERY_REQUIRED.name(),next.name(),objectMapper.createObjectNode().put("note",r.note()));audit(a,"JOB_RECONCILED",j,md);return mapper.toResponse(j);}

    private void requireNewMqttHello(Job job) {
        if(!job.isBeOrchestrated() || job.getRobot()==null)return;
        robotRepository.findByIdForUpdate(job.getRobot().getRobotId()).orElseThrow();
        var session=robotSessions.lock(job.getRobot().getRobotId()).orElse(null);
        if(session!=null) {
            // Stop server heartbeats so the worker reconnects and reconciles its journal.
            // The previous HELLO can retain a block from the now-closed recovery Job.
            session.setDispatchReady(false);session.setClosed(true);
            session.setFailureReason("Recovery closed; new HELLO/heartbeat required before dispatch");
        }
    }
    @Override @Transactional public JobResponse assignRobot(UUID id,UUID robotId,AccountPrincipal a,RequestMetadata md){Job j=jobRepository.lockById(id).orElseThrow(()->new ResourceNotFoundException("Job not found"));if(j.getStatus()!=JobStatus.QUEUED||j.getAcceptedAt()!=null)throw new BusinessRuleException("Robot can only be assigned before acceptance");Robot robot=robotRepository.findByIdForUpdate(robotId).orElseThrow(()->new ResourceNotFoundException("Robot not found: "+robotId));if(robot.getStatus()!=RobotStatus.IDLE||!jobRepository.findByRobotRobotIdAndStatusIn(robotId,List.of(JobStatus.QUEUED,JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED)).stream().filter(x->!x.getJobId().equals(id)).toList().isEmpty())throw new ConflictException("Robot is not available");if(!readiness.acceptsRobot(j.getWorkflow(),robot))throw new BusinessRuleException("Selected robot does not support this workflow transport");j.setRobot(robot);if(!j.isBeOrchestrated()){j.setDispatchCommandId(UUID.randomUUID());j.setDispatchExpiresAt(Instant.now(clock).plusSeconds(60));}wakeups.request(j);audit(a,"JOB_ROBOT_ASSIGNED",j,md);return mapper.toResponse(j);}
    @Override @Transactional public JobResponse assignStaff(UUID id,UUID staffId,AccountPrincipal a,RequestMetadata md){Job j=jobRepository.lockById(id).orElseThrow(()->new ResourceNotFoundException("Job not found"));Account staff=account(staffId);if(staff.getRole()!=Role.STAFF||staff.getStatus()!=AccountStatus.ACTIVE)throw new BusinessRuleException("Assigned account must be active STAFF");j.setAssignedStaffAccount(staff);wakeups.request(j);audit(a,"JOB_STAFF_ASSIGNED",j,md);return mapper.toResponse(j);}
    private void scope(Job j,AccountPrincipal a){staffScope.requireJobAccess(j,a);}
    private void validateWindow(Instant from,Instant to){if(from!=null&&to!=null&&!from.isBefore(to))throw new BusinessRuleException("from must be before to");}
    private <T> Page<T> page(List<T> values,Pageable p){int start=(int)Math.min(p.getOffset(),values.size());int end=Math.min(start+p.getPageSize(),values.size());return new PageImpl<>(values.subList(start,end),p,values.size());}
    private JobResponse safeJob(Job value){JobResponse r=mapper.toResponse(value);return new JobResponse(r.jobId(),r.workflowId(),r.serviceRequestId(),r.scheduleId(),r.robotId(),r.mapId(),r.assignedStaffAccountId(),r.jobCode(),r.status(),r.priority(),safeJson(r.inputJson()),safeJson(r.contextJson()),safeJson(r.resultJson()),r.workflowHash(),r.protocolVersion(),r.schemaVersion(),r.graphVersion(),r.dispatchCommandId(),r.dispatchExpiresAt(),r.queuedAt(),r.acceptedAt(),r.startedAt(),r.cancelCommandId(),r.cancelRequestedAt(),r.endedAt(),r.errorCode(),r.failureReason(),r.reconciledAt(),r.reconciliationNote(),r.rowVersion(),r.beOrchestrated(),r.selectedJobStepId());}
    private JobStepResponse safeStep(JobStep value){JobStepResponse r=mapper.toResponse(value);return new JobStepResponse(r.jobStepId(),r.jobTaskId(),r.stepId(),r.visitNo(),r.executionSequenceNo(),r.stepCodeSnapshot(),r.stepTypeSnapshot(),safeJson(r.resolvedInputsJson()),safeJson(r.resultJson()),r.status(),r.attemptNo(),null,r.timeoutSecSnapshot(),r.startedAt(),r.endedAt(),r.errorCode(),safeJson(r.errorDetailsJson()),r.rowVersion(),r.selectedAt(),r.interruptionReason(),r.interruptionRequestedAt());}
    private JobEventResponse safeEvent(JobEvent value,AccountPrincipal actor){JobEventResponse r=mapper.toResponse(value);UUID actorId=actor.role()==Role.ADMIN||Objects.equals(r.actorAccountId(),actor.accountId())?r.actorAccountId():null;return new JobEventResponse(r.jobEventId(),r.jobId(),r.robotId(),r.jobTaskId(),r.jobStepId(),actorId,r.producerType(),null,r.sequenceNo(),r.eventType(),r.correlationKey(),r.attemptNo(),r.previousStatus(),r.newStatus(),r.statusScope(),safeJson(r.eventDataJson()),r.occurredAt(),r.receivedAt(),r.persistedAt());}
    private com.fasterxml.jackson.databind.JsonNode safeJson(com.fasterxml.jackson.databind.JsonNode source){if(source==null)return null;com.fasterxml.jackson.databind.JsonNode copy=source.deepCopy();redact(copy);return copy;}
    private void redact(com.fasterxml.jackson.databind.JsonNode node){if(node==null)return;if(node.isObject()){var object=(com.fasterxml.jackson.databind.node.ObjectNode)node;List<String> names=new ArrayList<>();object.fieldNames().forEachRemaining(names::add);for(String name:names){String lower=name.toLowerCase(Locale.ROOT);if(lower.contains("secret")||lower.contains("password")||lower.contains("token")||lower.equals("proof")||lower.endsWith("_proof"))object.remove(name);else redact(object.get(name));}}else if(node.isArray())node.forEach(this::redact);}
    private boolean terminal(JobStatus s){return Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED).contains(s);}
    private Job job(UUID id){return jobRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Job not found: "+id));}
    private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Account not found: "+id));}
    private void event(Job j,AccountPrincipal a,String type,String previous,String next,com.fasterxml.jackson.databind.JsonNode data){Instant now=Instant.now(clock);eventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(j).robot(j.getRobot()).actorAccount(account(a.accountId())).producerType(JobEventProducerType.valueOf(a.role().name())).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType(type).previousStatus(previous).newStatus(next).statusScope("JOB").eventDataJson(data).occurredAt(now).receivedAt(now).persistedAt(now).build());}
    private void audit(AccountPrincipal a,String action,Job j,RequestMetadata md){auditService.record(account(a.accountId()),action,"Job",j.getJobId(),null,Map.of("status",j.getStatus().name()),md);}
}
