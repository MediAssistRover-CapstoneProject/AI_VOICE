package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.job.request.JobRequests;
import com.mediassistrover.backend.dto.job.request.MappingRecoveryRequest;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovery for cleanup STOP that never reached Pi; all published operations keep their actual
 * results.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MappingRecoveryService {
  private static final List<JobStatus> HELD =
      List.of(JobStatus.QUEUED, JobStatus.ACCEPTED, JobStatus.RUNNING, JobStatus.RECOVERY_REQUIRED);
  private static final Set<String> TERMINAL =
      Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
  private static final List<String> UNCERTAIN =
      List.of("PENDING", "PUBLISHING", "PUBLISHED", "ACKNOWLEDGED", "STARTED", "RECOVERY_REQUIRED");
  private final JobRepository jobs;
  private final JobStepRepository steps;
  private final JobTaskRepository tasks;
  private final RobotRepository robots;
  private final RobotMqttSessionRepository links;
  private final MappingSessionRepository maps;
  private final RobotCommandOutboxRepository commands;
  private final RobotMqttInboxRepository inbox;
  private final MqttOperationRecoveryReceiptRepository receipts;
  private final JobEventRepository events;
  private final StaffOperationalScopePolicy scope;
  private final MqttSessionRegistry registry;
  private final MappingLifecycleService lifecycle;
  private final MqttWire wire;
  private final AuditService audit;
  private final OrchestrationWorkService wakeups;
  private final ApplicationEventPublisher updates;
  private final Clock clock;

  public JsonNode evidence(UUID jobId, AccountPrincipal actor) {
    Job job =
        jobs.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
    authorize(job, actor);
    State state = inspect(job, false);
    ObjectNode result =
        wire.object()
            .put("recoveryType", "UNSENT_MAPPING_STOP")
            .put("jobId", jobId.toString())
            .put("status", job.getStatus().name())
            .put("canReconcile", state.blockers().isEmpty())
            .put(
                "selectedUnsentStop",
                unsentCleanup(job.getSelectedJobStep())
                    && state.commands().stream()
                        .filter(
                            c -> c.getJobStepId().equals(job.getSelectedJobStep().getJobStepId()))
                        .allMatch(this::neverSent));
    if (job.getSelectedJobStep() != null)
      result.put("jobStepId", job.getSelectedJobStep().getJobStepId().toString());
    if (state.link() != null) {
      result.put("transportSessionId", state.link().getTransportSessionId().toString());
      result.put(
          "lastHeartbeatAt",
          state.link().getLastHeartbeatAt() == null
              ? null
              : state.link().getLastHeartbeatAt().toString());
    }
    result.set("blockers", wire.tree(state.blockers()));
    result.put("requiresNewHelloAfterRecovery", true);
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public JsonNode reconcile(
      UUID jobId,
      String key,
      MappingRecoveryRequest request,
      AccountPrincipal principal,
      RequestMetadata metadata) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,150}")) fail("Invalid Idempotency-Key");
    Job job =
        jobs.lockById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
    Account actor = authorize(job, principal);
    JobStep requested =
        steps
            .lockById(request.jobStepId())
            .orElseThrow(() -> new ResourceNotFoundException("JobStep not found"));
    if (!jobId.equals(requested.getJobTask().getJob().getJobId()))
      throw new AccessDeniedException("JobStep is outside this Job");
    ObjectNode body =
        wire.object().put("recoveryType", "UNSENT_MAPPING_STOP").put("jobId", jobId.toString());
    body.set("request", wire.tree(request));
    var prior = receipts.findByJobStepIdAndIdempotencyKey(request.jobStepId(), key).orElse(null);
    if (prior != null) {
      if (!actor.getAccountId().equals(prior.getActorAccountId())
          || !body.equals(prior.getRequestJson()))
        throw new ConflictException("Recovery key reused with different actor/body");
      return prior.getResponseJson().deepCopy();
    }
    if (job.getSelectedJobStep() == null
        || !request.jobStepId().equals(job.getSelectedJobStep().getJobStepId()))
      fail("Selected cleanup STOP changed; reload recovery evidence");
    if (!Boolean.TRUE.equals(request.confirmedStopped())
        || request.outcome() == null
        || request.note() == null
        || request.note().isBlank()) fail("Recovery requires stop confirmation, outcome and note");
    State state = inspect(job, true);
    if (!state.blockers().isEmpty()) fail(String.join("; ", state.blockers()));
    if (!state.link().getTransportSessionId().equals(request.transportSessionId()))
      fail("Transport session changed; reload recovery evidence");
    Instant now = Instant.now(clock);
    JobStatus terminal =
        request.outcome() == JobRequests.ReconcileOutcome.CONFIRM_CANCELLED
            ? JobStatus.CANCELLED
            : JobStatus.FAILED;
    var before =
        Map.<String, Object>of(
            "status",
            job.getStatus().name(),
            "mappingState",
            state.map().getState(),
            "jobStepId",
            request.jobStepId());
    for (var command : state.commands())
      if (command.getJobStepId().equals(request.jobStepId())) command.setState("ABANDONED");
    for (var visit : steps.findByJobId(jobId))
      if (visit.getStatus() == JobStepStatus.PENDING) {
        visit.setStatus(
            terminal == JobStatus.CANCELLED ? JobStepStatus.CANCELLED : JobStepStatus.FAILED);
        visit.setEndedAt(now);
      }
    for (var task : tasks.findByJobJobIdOrderBySequenceNoAsc(jobId))
      if (Set.of(JobTaskStatus.PENDING, JobTaskStatus.RUNNING).contains(task.getStatus())) {
        task.setStatus(
            terminal == JobStatus.CANCELLED ? JobTaskStatus.CANCELLED : JobTaskStatus.FAILED);
        task.setEndedAt(now);
      }
    lifecycle.reconciled(job);
    job.setStatus(terminal);
    job.setEndedAt(now);
    job.setReconciledAt(now);
    job.setReconciledByAccount(actor);
    job.setReconciliationNote(request.note());
    if (job.getServiceRequest() != null) {
      job.getServiceRequest().setStatus(ServiceRequestStatus.valueOf(terminal.name()));
      if (terminal == JobStatus.CANCELLED) job.getServiceRequest().setCancelledAt(now);
      else job.getServiceRequest().setCompletedAt(now);
    }
    job.getRobot().setStatus(RobotStatus.STARTING);
    state.link().setDispatchReady(false);
    state.link().setClosed(true);
    state
        .link()
        .setFailureReason(
            "Unsent mapping STOP reconciled; new HELLO/heartbeat required before dispatch");
    UUID receiptId = UUID.randomUUID();
    ObjectNode result =
        wire.object()
            .put("receiptId", receiptId.toString())
            .put("jobId", jobId.toString())
            .put("jobStepId", request.jobStepId().toString())
            .put("status", terminal.name())
            .put("stopWasExecuted", false)
            .put("localArtifactsPreserved", true)
            .put("requiresNewHello", true);
    receipts.save(
        MqttOperationRecoveryReceipt.builder()
            .receiptId(receiptId)
            .jobStepId(request.jobStepId())
            .actorAccountId(actor.getAccountId())
            .idempotencyKey(key)
            .requestJson(body)
            .responseJson(result)
            .createdAt(now)
            .build());
    events.save(
        JobEvent.builder()
            .jobEventId(UUID.randomUUID())
            .job(job)
            .jobStep(requested)
            .jobTask(requested.getJobTask())
            .robot(job.getRobot())
            .actorAccount(actor)
            .producerType(JobEventProducerType.valueOf(actor.getRole().name()))
            .producerSessionId(UUID.randomUUID())
            .sequenceNo(1)
            .eventType("MAPPING_UNSENT_STOP_RECONCILED")
            .statusScope("JOB")
            .previousStatus(JobStatus.RECOVERY_REQUIRED.name())
            .newStatus(terminal.name())
            .eventDataJson(body)
            .occurredAt(now)
            .receivedAt(now)
            .persistedAt(now)
            .build());
    audit.record(
        actor,
        "MAPPING_UNSENT_STOP_RECONCILED",
        "Job",
        jobId,
        before,
        Map.of("receipt", result),
        metadata);
    wakeups.request(job);
    updates.publishEvent(new RuntimeChanged(jobId));
    return result;
  }

  private State inspect(Job job, boolean lock) {
    List<String> blockers = new ArrayList<>();
    if (job.getStatus() != JobStatus.RECOVERY_REQUIRED || !job.isBeOrchestrated())
      blockers.add("JOB_NOT_IN_BE_RECOVERY");
    if (job.getRobot() == null)
      return new State(null, null, List.of(), List.of("JOB_HAS_NO_ROBOT"));
    UUID robotId = job.getRobot().getRobotId();
    if (lock)
      robots
          .findByIdForUpdate(robotId)
          .orElseThrow(() -> new ResourceNotFoundException("Robot not found"));
    RobotMqttSession link = (lock ? links.lock(robotId) : links.findById(robotId)).orElse(null);
    MappingSession map = maps.findByJobId(job.getJobId()).orElse(null);
    List<RobotCommandOutbox> all = commands.findByJobIdOrderByCreatedAt(job.getJobId());
    if (lock) all = all.stream().map(c -> commands.lock(c.getCommandId()).orElseThrow()).toList();
    JobStep stop = job.getSelectedJobStep();
    if (!unsentCleanup(stop))
      blockers.add("SELECTED_STEP_IS_NOT_AN_UNSTARTED_MAPPING_CLEANUP_STOP");
    if (map == null || !robotId.equals(map.getRobotId()) || !map.isPotentiallyOpen())
      blockers.add("NO_HELD_MAPPING_SESSION");
    if (map != null
        && stop != null
        && !map.getSessionId()
            .toString()
            .equals(stop.getResolvedInputsJson().path("mapping_session_id").asText()))
      blockers.add("STOP_MAPPING_SESSION_MISMATCH");
    Instant now = Instant.now(clock);
    if (link == null
        || !registry.owned(link)
        || !link.isHandshakeAcked()
        || link.getLastHeartbeatAt() == null
        || !link.getLastHeartbeatAt().isAfter(now.minusSeconds(15))
        || link.getLastHeartbeatAt().isAfter(now.plusSeconds(1))
        || link.getHelloReceivedAt() == null
        || link.getLastHeartbeatAt().isBefore(link.getHelloReceivedAt()))
      blockers.add("FRESH_PI_HELLO_AND_HEARTBEAT_REQUIRED");
    JsonNode heartbeat = link == null ? wire.object() : link.getHeartbeatJson();
    JsonNode hello = link == null ? wire.object() : link.getHelloJson();
    if (heartbeat == null) heartbeat = wire.object();
    if (hello == null) hello = wire.object();
    if (!"IDLE".equals(heartbeat.path("status").asText())
        || !heartbeat.path("activeJobStepId").isNull()) blockers.add("PI_NOT_IDLE");
    JsonNode odom = heartbeat.path("telemetry").path("odom_speed");
    if (!bounded(odom.path("value"), .01) || !bounded(odom.path("age_sec"), .5))
      blockers.add("FRESH_STATIONARY_ODOMETRY_REQUIRED");
    if (map != null
        && (!closedReport(hello.path("mappingSession"), job, map)
            || !closedReport(heartbeat.path("mappingSession"), job, map)))
      blockers.add("MATCHING_CLOSED_PI_MAPPING_REQUIRED");
    Map<String, JsonNode> journal = new HashMap<>();
    if (!hello.path("states").isArray()) blockers.add("TERMINAL_PI_JOURNAL_REQUIRED");
    for (JsonNode entry : hello.path("states")) {
      if (!TERMINAL.contains(entry.path("status").asText())) blockers.add("UNFINISHED_PI_JOURNAL");
      JobStep known = null;
      try {
        known = steps.findById(UUID.fromString(entry.path("jobStepId").asText())).orElse(null);
      } catch (IllegalArgumentException ignored) {
      }
      if (known == null
          || !known.getOperationKey().equals(entry.path("operationKey").asText())
          || !known.getJobTask().getJob().getJobId().toString().equals(entry.path("jobId").asText())
          || known.getJobTask().getJob().getRobot() == null
          || !robotId.equals(known.getJobTask().getJob().getRobot().getRobotId()))
        blockers.add("UNKNOWN_OR_FOREIGN_PI_JOURNAL");
      if (journal.put(entry.path("operationKey").asText(), entry) != null)
        blockers.add("DUPLICATE_PI_JOURNAL_OPERATION");
    }
    int executed = 0;
    for (var command : all) {
      if (stop != null && command.getJobStepId().equals(stop.getJobStepId())) {
        if (!neverSent(command))
          blockers.add("CLEANUP_STOP_MAY_HAVE_BEEN_SENT_USE_OPERATION_RECOVERY");
        continue;
      }
      executed++;
      JsonNode entry = journal.get(command.getOperationKey());
      if (!verifiedReceipt(command, job, robotId)
          || entry == null
          || !TERMINAL.contains(entry.path("status").asText())
          || !command.getJobStepId().toString().equals(entry.path("jobStepId").asText())
          || !job.getJobId().toString().equals(entry.path("jobId").asText()))
        blockers.add("PUBLISHED_OPERATION_STOP_RECEIPT_AND_JOURNAL_REQUIRED");
    }
    if (executed == 0) blockers.add("NO_PREVIOUS_VERIFIED_PI_OPERATION");
    if (steps.findByJobId(job.getJobId()).stream()
        .anyMatch(
            s ->
                (stop == null || !s.getJobStepId().equals(stop.getJobStepId()))
                    && Set.of(
                            JobStepStatus.RUNNING,
                            JobStepStatus.RETRYING,
                            JobStepStatus.RECOVERY_REQUIRED)
                        .contains(s.getStatus()))) blockers.add("OTHER_UNFINISHED_JOB_STEP");
    if (jobs.findByRobotRobotIdAndStatusIn(robotId, HELD).stream()
        .anyMatch(j -> !j.getJobId().equals(job.getJobId()))) blockers.add("OTHER_HELD_ROBOT_JOB");
    if (commands.findByRobotIdAndStateIn(robotId, UNCERTAIN).stream()
        .anyMatch(c -> !c.getJobId().equals(job.getJobId())))
      blockers.add("OTHER_UNCERTAIN_ROBOT_COMMAND");
    return new State(map, link, all, blockers.stream().distinct().toList());
  }

  private boolean verifiedReceipt(RobotCommandOutbox command, Job job, UUID robotId) {
    if (!"EXECUTE_STEP".equals(command.getCommandType())
        || !"COMPLETED".equals(command.getState())
        || command.getSendAttempts() < 1
        || !Boolean.TRUE.equals(command.getPhysicalStopConfirmed())
        || !robotId.equals(command.getRobotId())) return false;
    JsonNode receipt = command.getResultEnvelopeJson();
    if (receipt == null
        || !Set.of("STEP_RESULT", "STEP_ERROR").contains(receipt.path("messageType").asText())
        || !job.getJobId().toString().equals(receipt.path("jobId").asText())
        || !command.getJobStepId().toString().equals(receipt.path("jobStepId").asText())
        || !robotId.toString().equals(receipt.path("robotId").asText())
        || !command.getOperationKey().equals(receipt.path("operationKey").asText())
        || !receipt.path("payload").path("physicalStopConfirmed").asBoolean()) return false;
    try {
      RobotMqttInbox accepted =
          inbox.findById(UUID.fromString(receipt.path("messageId").asText())).orElse(null);
      return accepted != null
          && Boolean.TRUE.equals(accepted.getAccepted())
          && "PROCESSED".equals(accepted.getState())
          && robotId.equals(accepted.getRobotId())
          && receipt.equals(accepted.getRawEnvelope());
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private boolean unsentCleanup(JobStep step) {
    return step != null
        && step.isMappingCleanup()
        && step.getStatus() == JobStepStatus.PENDING
        && step.getStartedAt() == null
        && MappingLifecycleService.mapping(step)
        && "STOP".equals(step.getResolvedInputsJson().path("action").asText());
  }

  private boolean neverSent(RobotCommandOutbox command) {
    return "EXECUTE_STEP".equals(command.getCommandType())
        && "PENDING".equals(command.getState())
        && command.getSendAttempts() == 0
        && command.getLastPublishAt() == null
        && command.getAcknowledgedAt() == null
        && command.getResultEnvelopeJson() == null
        && command.getPhysicalStopConfirmed() == null;
  }

  private boolean closedReport(JsonNode report, Job job, MappingSession map) {
    if (!report.isObject()
        || !report.path("session_open").isBoolean()
        || report.path("session_open").asBoolean()
        || !job.getJobId().toString().equals(report.path("job_id").asText())
        || !map.getSessionId().toString().equals(report.path("session_id").asText())
        || !Set.of("SAVED_LOCAL", "SAVED", "ABORTED", "STOPPED", "OPERATOR_RECONCILED")
            .contains(report.path("state").asText())) return false;
    JsonNode artifact = report.path("artifact_id");
    if (Set.of("SAVED_LOCAL", "SAVED").contains(report.path("state").asText()))
      return map.getArtifactId().toString().equals(artifact.asText());
    return artifact.isMissingNode()
        || artifact.isNull()
        || map.getArtifactId().toString().equals(artifact.asText());
  }

  private boolean bounded(JsonNode value, double maximum) {
    return value.isNumber()
        && Double.isFinite(value.asDouble())
        && value.asDouble() >= 0
        && value.asDouble() <= maximum;
  }

  private Account authorize(Job job, AccountPrincipal actor) {
    scope.requireJobAccess(job, actor);
    return scope.requireCurrentStaffOrAdmin(actor);
  }

  private void fail(String reason) {
    throw new BusinessRuleException(reason);
  }

  private record State(
      MappingSession map,
      RobotMqttSession link,
      List<RobotCommandOutbox> commands,
      List<String> blockers) {}
}
