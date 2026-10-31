package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class MqttPublicationPlans {
    private final RobotCommandOutboxRepository commands;
    private final RobotMqttInboxRepository inbox;
    private final RobotMqttSessionRepository sessions;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final RobotRepository robots;
    private final MqttSessionRegistry registry;
    private final MqttJobSafety safety;
    private final OrchestrationWorkService wakeups;
    private final MqttProperties properties;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.mediassistrover.backend.service.NavigationProfileService navigationProfiles;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final com.mediassistrover.backend.ai.AiSensingService ai;
    private final MqttRolloutService rollout;
    private final com.mediassistrover.backend.validation.StepExecutionPolicy executionPolicy;
    public record Publication(UUID id,UUID robotId,UUID sessionId,JsonNode payload) { }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Publication command(UUID id) {
        RobotCommandOutbox initial=commands.findById(id).orElseThrow();
        Job job=jobs.lockById(initial.getJobId()).orElseThrow();robots.findByIdForUpdate(initial.getRobotId()).orElseThrow();
        RobotMqttSession session=sessions.lock(initial.getRobotId()).orElse(null);RobotCommandOutbox command=commands.lock(id).orElseThrow();
        entityManager.refresh(command,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        boolean cancel="CANCEL_STEP".equals(command.getCommandType());
        JobStep action=steps.findById(command.getJobStepId()).orElseThrow();boolean cleanup=action.isMappingCleanup();
        if(!cancel && !cleanup && !rollout.dispatchAllowed())return null;
        if(!properties.manages(command.getRobotId()) || !cancel && !cleanup && (!properties.isDispatchEnabled()
                || !properties.getEnabledStepTypes().contains(command.getPayloadJson().path("step").path("stepType").asText())))return null;
        if (!cancel && !cleanup && (executionPolicy.isServer(action, action.getResolvedInputsJson())
                || !registry.advertises(session, action.getStepTypeSnapshot()))) return null;
        if (!Set.of("PENDING","PUBLISHING","PUBLISHED").contains(command.getState())
                || job.getStatus()!=JobStatus.RUNNING && !(cancel && job.getStatus()==JobStatus.RECOVERY_REQUIRED)) return null;
        if(!cancel && (job.getCancelRequestedAt()!=null && !cleanup || commands.findByJobStepIdAndCommandType(command.getJobStepId(),"CANCEL_STEP").isPresent()))return null;
        Instant now=Instant.now(clock);
        if (!command.getExpiresAt().isAfter(now)) {
            if (cancel || command.getSendAttempts()>0) {command.setState("RECOVERY_REQUIRED");safety.recovery(job,cancel?"Cancel command expired without stop evidence":"Published command expired without a business ACK");}
            else {
                command.setState("EXPIRED");JobStep step=steps.lockById(command.getJobStepId()).orElseThrow();step.setStatus(JobStepStatus.TIMED_OUT);step.setEndedAt(now);step.setErrorCode("COMMAND_EXPIRED_BEFORE_PUBLISH");wakeups.request(job);
            }
            return null;
        }
        if (!(cancel?registry.owned(session) && session.isHandshakeAcked() && session.getLastHeartbeatAt()!=null
                && session.getLastHeartbeatAt().isAfter(now.minusSeconds(15)):registry.fresh(session))) return null;
        if(!cancel && com.mediassistrover.backend.ai.AiSensingService.ai(action) && !ai.publicationReady(command))return null;
        if(!cancel && "MOVE".equals(command.getPayloadJson().path("step").path("stepType").asText())
                && !navigationProfiles.publicationReady(job.getRobot(),session,command)) {
            if(command.getSendAttempts()>0) {command.setState("RECOVERY_REQUIRED");safety.recovery(job,"Pi navigation profile changed during a published MOVE");}
            return null;
        }
        if (!session.getTransportSessionId().toString().equals(command.getPayloadJson().path("transportSessionId").asText())) {
            if (command.getSendAttempts()>0 && !cancel) return null; // Cancel addresses the same operation; execute needs HELLO journal.
            ((com.fasterxml.jackson.databind.node.ObjectNode)command.getPayloadJson()).put("transportSessionId",session.getTransportSessionId().toString());
        }
        if (command.getLastPublishAt()!=null && command.getLastPublishAt().plusSeconds(properties.getRetryIntervalSec()).isAfter(now)) return null;
        if(!cancel && command.getSendAttempts()==0)mapping.publishing(action);
        command.setSendAttempts(command.getSendAttempts()+1);command.setLastPublishAt(now);command.setState("PUBLISHING");
        return new Publication(id,command.getRobotId(),session.getTransportSessionId(),command.getPayloadJson().deepCopy());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void commandPublished(UUID id,UUID sessionId) {
        RobotCommandOutbox command=commands.lock(id).orElseThrow();
        if ("PUBLISHING".equals(command.getState()) && command.getPayloadJson().path("transportSessionId").asText().equals(sessionId.toString())) command.setState("PUBLISHED");
    }
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Publication acknowledgement(UUID id,boolean force) {
        RobotMqttInbox row=inbox.findById(id).orElseThrow();
        if (row.getResponseJson()==null || !force && row.getAckPublishedAt()!=null) return null;
        RobotMqttSession session=sessions.findById(row.getRobotId()).orElse(null);
        if (Boolean.TRUE.equals(row.getAccepted()) || !"HELLO".equals(row.getMessageType()))
            if (!registry.owned(session) || !row.getTransportSessionId().equals(session.getTransportSessionId())) return null;
        return new Publication(id,row.getRobotId(),row.getTransportSessionId(),row.getResponseJson().deepCopy());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void acknowledged(UUID id,UUID sessionId) {
        RobotMqttInbox initial=inbox.findById(id).orElseThrow();
        RobotMqttSession session=sessions.lock(initial.getRobotId()).orElse(null);
        RobotMqttInbox row=inbox.lock(id).orElseThrow();
        entityManager.refresh(row,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!row.getTransportSessionId().equals(sessionId)) return;
        row.setAckPublishedAt(Instant.now(clock));
        if ("HELLO".equals(row.getMessageType()) && Boolean.TRUE.equals(row.getAccepted())) {
            if (registry.owned(session) && sessionId.equals(session.getTransportSessionId())) session.setHandshakeAcked(true);
        }
    }
}
