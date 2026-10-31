package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.enums.WorkflowStatus;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.validation.WorkflowExecutionReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Read-only operational evidence, excluding credentials and business payloads. */
@Service @RequiredArgsConstructor
public class MqttOperationsStatusService {
    public record Queue(long count, Double oldestAgeSeconds) { }
    public record RobotSession(UUID robotId,boolean handshakeAccepted,boolean handshakeAcked,boolean dispatchReady,
                               Double heartbeatAgeSeconds,String failureReason) { }
    public record WorkflowReadiness(UUID workflowId,String workflowCode,int versionNo,String status,boolean acceptsNewJobs,
                                    boolean configurationReady,String blocker) { }
    public record Status(Instant observedAt,MqttRolloutService.State rollout,long legacyJobsBlockingCutover,
                         boolean mqttEnabled,boolean brokerConnected,boolean dispatchConfigured,boolean dispatchEffective,
                         Set<String> enabledStepTypes,Queue outbox,Queue unpublished,Queue inbox,Queue eventAcks,
                         long recoveryJobs,long openMappingSessions,Double meanAckSinceLastPublishMs,
                         List<RobotSession> robots,List<WorkflowReadiness> workflows) { }
    private final MqttRolloutService rollout;
    private final MqttProperties properties;
    private final MqttConnectionState connection;
    private final MqttSessionRegistry registry;
    private final RobotMqttSessionRepository sessions;
    private final WorkflowRepository workflows;
    private final WorkflowExecutionReadiness readiness;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Status status() {
        Instant now=Instant.now(clock);var control=rollout.state();
        var robotRows=properties.getRobotIds().stream().sorted().map(id->{
            var session=sessions.findById(id).orElse(null);
            return new RobotSession(id,session!=null && session.isHandshakeAccepted(),session!=null && session.isHandshakeAcked(),
                    properties.manages(id) && registry.fresh(session),session==null?null:age(now,session.getLastHeartbeatAt()),session==null?"HELLO required":session.getFailureReason());
        }).toList();
        var workflowRows=workflows.findAll().stream().filter(w->w.getSchemaVersion()==3 && w.getStatus()!=WorkflowStatus.ARCHIVED).map(w->{
            boolean ready=true;String blocker=null;
            try{readiness.requireReady(w);}catch(BusinessRuleException ex){ready=false;blocker=ex.getMessage();}
            if(w.getDefinitionHash()==null || w.getCompiledDefinitionJson()==null){ready=false;if(blocker==null)blocker="Validate and compile before publishing";}
            return new WorkflowReadiness(w.getWorkflowId(),w.getWorkflowCode(),w.getVersionNo(),w.getStatus().name(),w.isAcceptsNewJobs(),ready,blocker);
        }).sorted(Comparator.comparing(WorkflowReadiness::workflowCode).thenComparingInt(WorkflowReadiness::versionNo)).toList();
        boolean effective=properties.isEnabled() && connection.connected() && properties.isDispatchEnabled() && !control.dispatchHalted()
                && !properties.getEnabledStepTypes().isEmpty() && robotRows.stream().anyMatch(RobotSession::dispatchReady);
        return new Status(now,control,rollout.legacyBlockers(),properties.isEnabled(),connection.connected(),properties.isDispatchEnabled(),effective,
                Set.copyOf(properties.getEnabledStepTypes()),
                queue("RobotCommandOutbox","created_at","state IN ('PENDING','PUBLISHING','PUBLISHED','ACKNOWLEDGED','STARTED','RECOVERY_REQUIRED')",now),
                queue("RobotCommandOutbox","created_at","state IN ('PENDING','PUBLISHING','PUBLISHED') AND send_attempts=0",now),
                queue("RobotMqttInbox","received_at","state='PENDING'",now),
                queue("RobotMqttInbox","processed_at","response_json IS NOT NULL AND ack_published_at IS NULL",now),
                jdbc.queryForObject("SELECT COUNT(*) FROM Job WHERE status='RECOVERY_REQUIRED'",Long.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM MappingSession WHERE potentially_open=TRUE",Long.class),
                jdbc.queryForObject("SELECT AVG(TIMESTAMPDIFF(MICROSECOND,last_publish_at,acknowledged_at)/1000.0) FROM RobotCommandOutbox WHERE acknowledged_at>=last_publish_at",Double.class),robotRows,workflowRows);
    }
    private Queue queue(String table,String time,String predicate,Instant now) {
        // All SQL identifiers are constants defined above; no request-supplied SQL.
        return jdbc.queryForObject("SELECT COUNT(*),MIN("+time+") FROM "+table+" WHERE "+predicate,(rs,n)->
                new Queue(rs.getLong(1),rs.getTimestamp(2)==null?null:age(now,rs.getTimestamp(2).toInstant())));
    }
    private static Double age(Instant now,Instant at) { return at==null?null:Math.max(0,Duration.between(at,now).toMillis()/1000.0); }
}
