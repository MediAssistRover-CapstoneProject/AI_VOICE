package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor
public class MqttSessionRegistry {
    private final RobotMqttSessionRepository sessions;
    private final RobotRepository robots;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final RobotCommandOutboxRepository commands;
    private final FacilityMapRepository maps;
    private final RobotLocationHistoryRepository locations;
    private final RobotStatusHistoryRepository histories;
    private final OrchestrationWorkService wakeups;
    private final MqttJobSafety safety;
    private final MqttConnectionState connection;
    private final MqttProperties properties;
    private final Clock clock;
    private final RobotNavigationProfileRepository navigationProfiles;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final com.mediassistrover.backend.mapping.MappingPreviewService mappingPreviews;
    private final org.springframework.context.ApplicationEventPublisher applicationEvents;
    private final com.mediassistrover.backend.service.NavigationSessionService navigationSessions;
    private final com.mediassistrover.backend.mapping.MappingCompletionService mappingCompletion;
    private static final List<JobStatus> OCCUPIED=List.of(JobStatus.QUEUED,JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED);

    public boolean owned(RobotMqttSession session) {
        UUID currentConnection=connection.connectionId();
        return currentConnection!=null && session!=null && !session.isClosed() && session.isHandshakeAccepted()
                && connection.instanceId().equals(session.getOwnerInstanceId()) && currentConnection.equals(session.getConnectionId());
    }
    public RobotMqttSession requireCurrent(UUID robot,UUID transport) {
        RobotMqttSession session=sessions.lock(robot).orElseThrow(()->new MqttSessionUnavailableException("MQTT HELLO required"));
        if (!owned(session) || !session.getTransportSessionId().equals(transport)) throw new MqttSessionUnavailableException("Stale MQTT session");
        return session;
    }
    public boolean fresh(RobotMqttSession session) {
        return owned(session) && session.isHandshakeAcked() && session.isDispatchReady() && session.getLastHeartbeatAt()!=null
                && session.getLastHeartbeatAt().isAfter(Instant.now(clock).minusSeconds(15));
    }
    public boolean advertises(RobotMqttSession session, String stepType) {
        if (session == null || session.getHelloJson() == null || stepType == null) return false;
        for (JsonNode enabled : session.getHelloJson().path("enabledStepTypes")) {
            if (stepType.equals(enabled.asText())) return true;
        }
        return false;
    }

    /** Processor acquired active Job locks before this call; always lock Robot before Session. */
    public RobotMqttSession hello(MqttWire.Envelope e,List<Job> active) {
        UUID currentConnection=connection.connectionId();
        if (currentConnection==null) throw new MqttSessionUnavailableException("MQTT subscription is not ready");
        Robot robot=robots.findByIdForUpdate(e.robotId()).orElseThrow(()->new BusinessRuleException("Unknown MQTT robot"));
        RobotMqttSession prior=sessions.lock(e.robotId()).orElse(null);
        boolean mappingProcessChanged=prior!=null && !prior.getProducerSessionId().equals(e.producerSessionId());
        boolean sameTransport=prior!=null && owned(prior) && prior.getTransportSessionId().equals(e.transportSessionId());
        if (sameTransport && !prior.getProducerSessionId().equals(e.producerSessionId())) throw new BusinessRuleException("New Pi process requires a new transport session");
        RobotMqttSession session=prior==null?new RobotMqttSession():prior;
        boolean reconciled=true;String reason=null;
        JsonNode hello=e.payload();
        navigationSessions.hello(e.robotId(),e.producerSessionId(),hello.path("navigationSession"));
        Map<String,JsonNode> journal=new HashMap<>();
        for (JsonNode state:hello.path("states")) {
            if (journal.put(state.path("operationKey").asText(),state)!=null) throw new BusinessRuleException("Duplicate operation in HELLO journal");
            JobStep known=steps.findById(UUID.fromString(state.path("jobStepId").asText())).orElse(null);
            if (known==null || !known.getOperationKey().equals(state.path("operationKey").asText())
                    || !known.getJobTask().getJob().getJobId().equals(UUID.fromString(state.path("jobId").asText()))
                    || known.getJobTask().getJob().getRobot()==null || !known.getJobTask().getJob().getRobot().getRobotId().equals(e.robotId())) {
                reconciled=false;reason="Unknown or foreign operation in HELLO journal";
            }
            if ("RECOVERY_REQUIRED".equals(state.path("status").asText())) {reconciled=false;reason="Pi journal requires recovery";}
        }
        for (RobotCommandOutbox command:commands.findByRobotIdAndStateIn(e.robotId(),List.of("PUBLISHING","PUBLISHED","ACKNOWLEDGED","STARTED","RECOVERY_REQUIRED"))) {
            if("CANCEL_STEP".equals(command.getCommandType()))continue;
            JsonNode state=journal.get(command.getOperationKey());
            if (state==null || !UUID.fromString(state.path("jobStepId").asText()).equals(command.getJobStepId())
                    || (prior!=null && !prior.getProducerSessionId().equals(e.producerSessionId()))) {
                reconciled=false;reason="In-flight operation cannot be reconciled after reconnect/restart";
            } else if (!sameTransport && !"RECOVERY_REQUIRED".equals(command.getState())) {
                ObjectNode payload=((ObjectNode)command.getPayloadJson()).deepCopy();payload.put("transportSessionId",e.transportSessionId().toString());command.setPayloadJson(payload);
            }
        }
        for (Job job:active) if (!job.isBeOrchestrated() || job.getStatus()==JobStatus.RECOVERY_REQUIRED) {reconciled=false;reason="Robot is held by a legacy or recovery Job";}
        if (!reconciled) for (Job job:active) safety.recovery(job,reason);
        session.setRobotId(e.robotId());session.setTransportSessionId(e.transportSessionId());session.setProducerSessionId(e.producerSessionId());
        session.setOwnerInstanceId(connection.instanceId());session.setConnectionId(currentConnection);
        session.setHandshakeAccepted(true);session.setHandshakeAcked(sameTransport && session.isHandshakeAcked());session.setClosed(false);
        if(!mapping.telemetryMatches(e.robotId(),hello.path("mappingSession"))
                || active.stream().anyMatch(j->mapping.open(j.getJobId())) && (!hello.path("mappingEnabled").asBoolean()
                || mappingProcessChanged)) {
            reconciled=false;reason="Unknown or foreign open mapping session";active.forEach(j->safety.recovery(j,"Unknown or foreign open mapping session"));robot.setStatus(RobotStatus.ERROR);
        }
        session.setDispatchReady(reconciled);session.setFailureReason(reason);
        session.setHelloJson(hello.deepCopy());session.setHelloReceivedAt(Instant.now(clock));
        if (!sameTransport) {session.setLastHeartbeatAt(null);session.setHeartbeatJson(null);}
        robot.setRuntimeVersion(hello.path("runtimeVersion").asText());robot.setProtocolVersion(1);robot.setCapabilitiesJson(hello.path("capabilities").deepCopy());
        if (!currentConnection.equals(connection.connectionId()))
            throw new MqttSessionUnavailableException("MQTT connection changed during HELLO; retry on the current connection");
        sessions.save(session);
        return session;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void heartbeat(MqttWire.Envelope e) {
        List<Job> active=jobs.lockRobotJobs(e.robotId(),OCCUPIED);
        Robot robot=robots.findByIdForUpdate(e.robotId()).orElseThrow();
        RobotMqttSession session=requireCurrent(e.robotId(),e.transportSessionId());
        if (!session.getProducerSessionId().equals(e.producerSessionId())) throw new BusinessRuleException("Heartbeat producer does not match HELLO");
        JsonNode p=e.payload().deepCopy();Instant now=Instant.now(clock);
        var context=navigationSessions.current(e.robotId());
        if(session.getHelloJson().hasNonNull("navigationSession") && (context==null || !navigationSessions.peer(e.robotId(),session)
                || !navigationSessions.matches(context,p.path("navigationSession")))) {
            session.setDispatchReady(false);session.setFailureReason("NAVIGATION_SESSION_CLOSED_OR_CHANGED");active.forEach(j->safety.recovery(j,session.getFailureReason()));
        }
        if (e.time().isAfter(now.plusSeconds(5)) || e.time().isBefore(now.minusSeconds(15))) throw new BusinessRuleException("Stale/future MQTT heartbeat");
        if (session.getHeartbeatJson()!=null && session.getLastHeartbeatAt()!=null && e.time().isBefore(Instant.parse(session.getHeartbeatJson().path("occurredAt").asText()))) return;
        UUID mapId=p.path("currentMapId").isNull()?null:UUID.fromString(p.path("currentMapId").asText());
        boolean mappingOpen=com.mediassistrover.backend.mapping.MappingLifecycleService.reportsOpen(p.path("mappingSession"));
        if(!mapping.telemetryMatches(e.robotId(),p.path("mappingSession")) || mappingOpen && (mapId!=null || !p.path("mapAssetHash").isNull())) {
            session.setDispatchReady(false);session.setFailureReason("Mapping heartbeat scope/map identity mismatch");active.forEach(j->safety.recovery(j,session.getFailureReason()));
        }
        FacilityMap map=mapId==null || mappingOpen?null:maps.findById(mapId).orElseThrow(()->new BusinessRuleException("Heartbeat map does not exist"));
        RobotNavigationProfile deployed=navigationProfiles.findById(e.robotId()).orElse(null);
        boolean incompatibleMove=commands.findByRobotIdAndStateIn(e.robotId(),List.of("PUBLISHING","PUBLISHED","ACKNOWLEDGED","STARTED")).stream()
                .filter(c->c.getSendAttempts()>0 && "MOVE".equals(c.getPayloadJson().path("step").path("stepType").asText()))
                .anyMatch(c->deployed==null || c.getNavigationProfileHash()==null || !c.getNavigationProfileHash().equals(deployed.getProfileHash())
                        || !deployed.getMapAssetHash().equalsIgnoreCase(p.path("mapAssetHash").asText()));
        if(incompatibleMove) {session.setDispatchReady(false);session.setFailureReason("Navigation profile/map hash changed during MOVE");active.forEach(j->safety.recovery(j,session.getFailureReason()));}
        if (active.stream().anyMatch(j->j.getMap()!=null && !mapping.open(j.getJobId()) && (map==null || !j.getMap().getMapId().equals(map.getMapId())))) {
            session.setDispatchReady(false);session.setFailureReason("Heartbeat map differs from held Job");active.forEach(j->safety.recovery(j,session.getFailureReason()));
        }
        if (!p.path("activeJobStepId").isNull()) {
            UUID id=UUID.fromString(p.path("activeJobStepId").asText());
            boolean known=active.stream().anyMatch(j->j.getSelectedJobStep()!=null && j.getSelectedJobStep().getJobStepId().equals(id));
            RobotCommandOutbox recent=commands.findByJobStepId(id).orElse(null);
            if (!known && (recent==null || !recent.getRobotId().equals(e.robotId()) || !"COMPLETED".equals(recent.getState()))) {
                session.setDispatchReady(false);session.setFailureReason("Unknown active Pi operation");active.forEach(j->safety.recovery(j,session.getFailureReason()));
            }
        }
        RobotStatus previous=robot.getStatus();
        boolean held=active.stream().anyMatch(j->Set.of(JobStatus.RUNNING,JobStatus.ACCEPTED).contains(j.getStatus()));
        boolean recovery=active.stream().anyMatch(j->j.getStatus()==JobStatus.RECOVERY_REQUIRED);
        RobotStatus next=recovery || "ERROR".equals(p.path("status").asText())?RobotStatus.ERROR:
                held || "BUSY".equals(p.path("status").asText()) || mappingOpen?RobotStatus.BUSY:RobotStatus.IDLE;
        robot.setStatus(next);robot.setLastHeartbeatAt(now);robot.setRuntimeVersion(p.path("runtimeVersion").asText());robot.setCurrentMap(map);
        robot.setMapAssetHash(mappingOpen || p.path("mapAssetHash").isNull()?null:p.path("mapAssetHash").asText().toLowerCase(Locale.ROOT));robot.setCapabilitiesJson(p.path("capabilities").deepCopy());
        JsonNode telemetry=p.path("telemetry"),battery=telemetry.path("battery");
        if (battery.has("value") && !battery.path("value").isNull() && battery.path("age_sec").asDouble()<=15) {
            robot.setBatteryRaw(battery.path("value").asInt());robot.setBatteryUnit(BatteryUnit.UNKNOWN);
            robot.setBatteryCapturedAt(e.time().minusMillis((long)(battery.path("age_sec").asDouble()*1000)));
        }
        if (previous!=next && !histories.existsById(e.messageId())) histories.save(RobotStatusHistory.builder().robotStatusHistoryId(e.messageId()).robot(robot)
                .previousStatus(previous).newStatus(next).reason("MQTT_HEARTBEAT").recordedAt(now).build());
        JsonNode pose=telemetry.path("map_pose");
        if(pose.path("fresh").asBoolean() && session.getHelloJson().hasNonNull("navigationSession")){
            JsonNode localization=p.path("telemetry").path("localization"),value=pose.path("value");
            if(context==null||!navigationSessions.matches(context,p.path("navigationSession"))||!localization.path("ready").asBoolean()
                    || !context.getSessionId().toString().equals(value.path("navigation_session_id").asText())
                    ||value.path("initialization_revision").asLong(-1)!=context.getInitializationRevision()
                    ||localization.path("initialization_revision").asLong(-1)!=context.getInitializationRevision()
                    ||localization.path("age_sec").asDouble(99)>.75){
                // A seed command and an older heartbeat can cross in flight. Keep the
                // transport alive, but never store/show that old pose as localized.
                ((ObjectNode)pose).put("fresh",false).putNull("value").putNull("age_sec");
                if(localization.isObject())((ObjectNode)localization).put("ready",false).put("reason","LOCALIZATION_REVISION_NOT_CONFIRMED");
            }
        }
        if(pose.path("fresh").asBoolean())validateFreshMapPose(pose,map,
                mappingOpen?p.path("mappingSession").path("session_id").asText():null);
        if (map!=null && !mappingOpen && active.stream().noneMatch(j->mapping.open(j.getJobId())) && pose.path("fresh").asBoolean() && !locations.existsById(e.messageId())) {
            JsonNode value=pose.path("value");locations.save(RobotLocationHistory.builder().robotLocationHistoryId(e.messageId()).robot(robot).map(map)
                    .frameId("map").source("MQTT_HEARTBEAT_MAP_POSE")
                    .xM(value.path("x_m").decimalValue()).yM(value.path("y_m").decimalValue()).yawRad(value.path("yaw_rad").decimalValue()).fresh(true)
                    .capturedAt(e.time().minusMillis((long)(pose.path("age_sec").asDouble()*1000))).receivedAt(now).build());
        }
        ObjectNode heartbeat=(ObjectNode)p.deepCopy();heartbeat.put("occurredAt",e.occurredAt());session.setHeartbeatJson(heartbeat);session.setLastHeartbeatAt(now);
        if(session.isDispatchReady()&&mappingOpen)for(var job:active)mappingCompletion.observe(job,p.path("mappingSession"),telemetry);
        if (next==RobotStatus.ERROR) session.setDispatchReady(false);
        for (Job job:active) if (job.isBeOrchestrated() && job.getStatus()!=JobStatus.RECOVERY_REQUIRED) wakeups.request(job);
        applicationEvents.publishEvent(new com.mediassistrover.backend.service.RobotRealtimeEventStream.Changed(e.robotId()));
    }

    private void validateFreshMapPose(JsonNode pose,FacilityMap map,String mappingSessionId) {
        JsonNode value=pose.path("value"),age=pose.path("age_sec");
        if(mappingSessionId!=null) {
            if(!value.isObject() || !value.path("map_id").isNull()
                    || !mappingSessionId.equals(value.path("mapping_session_id").asText()))
                throw new BusinessRuleException("SLAM map_pose must belong to the reported mapping session, with map_id=null");
        } else if(map==null)throw new BusinessRuleException("Fresh map_pose requires an assigned navigation map");
        if(!age.isNumber()||age.decimalValue().compareTo(BigDecimal.ZERO)<0||age.decimalValue().compareTo(BigDecimal.ONE)>0)
            throw new BusinessRuleException("Fresh map_pose age_sec must be between 0 and 1");
        if(mappingSessionId==null && (!value.isObject()||!map.getMapId().toString().equals(value.path("map_id").asText())))
            throw new BusinessRuleException("map_pose map_id does not match currentMapId");
        for(String field:List.of("x_m","y_m","yaw_rad"))if(!value.path(field).isNumber())
            throw new BusinessRuleException("map_pose "+field+" must be numeric");
        BigDecimal limit=new BigDecimal("99999999.9999");
        if(value.path("x_m").decimalValue().abs().compareTo(limit)>0||value.path("y_m").decimalValue().abs().compareTo(limit)>0)
            throw new BusinessRuleException("map_pose coordinates exceed supported map bounds");
        BigDecimal yaw=value.path("yaw_rad").decimalValue(),pi=new BigDecimal("3.141593");
        if(yaw.compareTo(pi.negate())<0||yaw.compareTo(pi)>0)
            throw new BusinessRuleException("map_pose yaw_rad must be between -3.141593 and 3.141593");
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void offline(UUID robotId,UUID transport) {
        List<Job> active=jobs.lockRobotJobs(robotId,OCCUPIED);robots.findByIdForUpdate(robotId).orElseThrow();
        RobotMqttSession session=sessions.lock(robotId).orElse(null);
        if (session==null || !transport.equals(session.getTransportSessionId())) return;
        close(session,active);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void expire(UUID robotId,UUID transport,Instant threshold) {
        List<Job> active=jobs.lockRobotJobs(robotId,OCCUPIED);robots.findByIdForUpdate(robotId).orElseThrow();
        RobotMqttSession session=sessions.lock(robotId).orElse(null);
        if(session==null || session.isClosed() || !transport.equals(session.getTransportSessionId()))return;
        Instant last=session.getLastHeartbeatAt()==null?session.getHelloReceivedAt():session.getLastHeartbeatAt();
        if(last.isAfter(threshold))return; // A heartbeat committed after the scheduler's scan wins.
        close(session,active);
    }
    private void close(RobotMqttSession session,List<Job> active) {
        session.setClosed(true);session.setDispatchReady(false);session.setFailureReason("MQTT session offline");
        for (Job job:active) if (mapping.open(job.getJobId()) || commands.findByRobotIdAndStateIn(session.getRobotId(),List.of("PUBLISHING","PUBLISHED","ACKNOWLEDGED","STARTED")).stream().anyMatch(c->c.getJobId().equals(job.getJobId())))
            safety.recovery(job,"MQTT disconnected with an in-flight operation");
        applicationEvents.publishEvent(new com.mediassistrover.backend.service.RobotRealtimeEventStream.Changed(session.getRobotId()));
    }
    public void camera(UUID robotId,UUID transport,JsonNode payload) {
        RobotMqttSession session=sessions.findById(robotId).orElse(null);
        if(!fresh(session) || !transport.equals(session.getTransportSessionId()) || !session.getHelloJson().path("mappingEnabled").asBoolean())return;
        mappingPreviews.acceptCamera(robotId,payload);
    }
    public void preview(UUID robotId,UUID transport,JsonNode payload) {
        RobotMqttSession session=sessions.findById(robotId).orElse(null);
        if (!fresh(session) || !transport.equals(session.getTransportSessionId()) || !session.getHelloJson().path("mappingEnabled").asBoolean()) return;
        mappingPreviews.accept(robotId,payload);
    }
}
