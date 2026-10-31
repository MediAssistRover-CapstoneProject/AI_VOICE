package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.robot.response.RobotResponses.RobotResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.mapper.RobotMapper;
import com.mediassistrover.backend.mqtt.MqttSessionRegistry;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class RobotRealtimeSnapshotService {
    public record Pose(UUID locationId,UUID robotId,UUID mapId,UUID jobId,BigDecimal xM,BigDecimal yM,
                       BigDecimal yawRad,BigDecimal localizationConfidence,String frameId,String source,
                       boolean fresh,Instant capturedAt,Instant receivedAt) { }
    public record Snapshot(Instant observedAt,RobotResponse robot,boolean mqttConnected,boolean heartbeatFresh,
                           Double heartbeatAgeSeconds,boolean dispatchReady,String sessionFailureReason,
                           UUID activeJobId,UUID selectedJobStepId,String selectedStepType,String selectedStepCode,
                           Pose latestPose,com.fasterxml.jackson.databind.JsonNode navigationSession) { }

    private final RobotRepository robots;
    private final RobotLocationHistoryRepository locations;
    private final RobotMqttSessionRepository sessions;
    private final JobRepository jobs;
    private final RobotMapper mapper;
    private final MqttSessionRegistry registry;
    private final Clock clock;
    private final NavigationSessionService navigationSessions;
    @Value("${app.operations.robot-pose-freshness-seconds:3}") private long poseFreshnessSeconds;

    @Transactional(readOnly=true)
    public Snapshot snapshot(UUID robotId) {
        Instant now=Instant.now(clock);Robot robot=robots.findById(robotId)
                .orElseThrow(()->new ResourceNotFoundException("Robot not found: "+robotId));
        RobotMqttSession session=sessions.findById(robotId).orElse(null);
        boolean mqttConnected=registry.owned(session);
        boolean heartbeatFresh=mqttConnected&&session.getLastHeartbeatAt()!=null
                &&session.getLastHeartbeatAt().isAfter(now.minusSeconds(15));
        Double heartbeatAge=session==null||session.getLastHeartbeatAt()==null?null:
                Math.max(0,Duration.between(session.getLastHeartbeatAt(),now).toMillis()/1000.0);
        Job active=jobs.findByRobotRobotIdAndStatusIn(robotId,List.of(JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED))
                .stream().min(Comparator.comparing(Job::getQueuedAt)).orElse(null);
        JobStep selected=active==null?null:active.getSelectedJobStep();
        Pose pose=locations.findFirstByRobotRobotIdOrderByCapturedAtDesc(robotId).map(value->{
            boolean fresh=value.isFresh()&&!value.getCapturedAt().isBefore(now.minusSeconds(poseFreshnessSeconds))
                    && mqttConnected&&heartbeatFresh&&session.getHeartbeatJson()!=null
                    &&session.getHeartbeatJson().path("telemetry").path("map_pose").path("fresh").asBoolean()
                    &&navigationSessions.localized(robotId,session)
                    &&!value.getCapturedAt().isBefore(session.getHelloReceivedAt())
                    && robot.getCurrentMap()!=null&&robot.getCurrentMap().getMapId().equals(value.getMap().getMapId());
            return new Pose(value.getRobotLocationHistoryId(),robotId,value.getMap().getMapId(),
                    value.getJob()==null?null:value.getJob().getJobId(),value.getXM(),value.getYM(),value.getYawRad(),
                    value.getLocalizationConfidence(),value.getFrameId()==null?"map":value.getFrameId(),
                    value.getSource()==null?"LEGACY_LOCATION_HISTORY":value.getSource(),fresh,
                    value.getCapturedAt(),value.getReceivedAt());
        }).orElse(null);
        return new Snapshot(now,mapper.toResponse(robot),mqttConnected,heartbeatFresh,heartbeatAge,
                registry.fresh(session),session==null?"HELLO required":session.getFailureReason(),
                active==null?null:active.getJobId(),selected==null?null:selected.getJobStepId(),
                selected==null?null:selected.getStepTypeSnapshot(),selected==null?null:selected.getStepCodeSnapshot(),pose,navigationSessions.status(robotId));
    }
}
