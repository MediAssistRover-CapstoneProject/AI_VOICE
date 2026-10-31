package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class NavigationProfileService {
    private final RobotRepository robots;
    private final RobotNavigationProfileRepository profiles;
    private final RobotMqttSessionRepository sessions;
    private final EndpointRepository endpoints;
    private final ZoneRepository zones;
    private final JobRepository jobs;
    private final RobotCommandOutboxRepository commands;
    private final MqttSessionRegistry registry;
    private final MqttProperties properties;
    private final MqttWire wire;
    private final OrchestrationWorkService work;
    private final Clock clock;
    private final NavigationSessionService navigationSessions;
    private static final List<JobStatus> HELD=List.of(JobStatus.QUEUED,JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED);
    private static final List<String> IN_FLIGHT=List.of("PUBLISHING","PUBLISHED","ACKNOWLEDGED","STARTED","RECOVERY_REQUIRED");
    public record Manifest(String profileVersion,UUID robotId,String profileHash,JsonNode profile) { }
    public record Confirm(@NotNull UUID transportSessionId,@NotNull UUID producerSessionId,@NotNull UUID mapId,
                          @NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String mapAssetHash,
                          @NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String profileHash) { }
    public record Receipt(UUID robotId,UUID transportSessionId,String profileHash,boolean duplicate,Instant acknowledgedAt) { }

    @Transactional(readOnly=true)
    public Manifest manifest(DevicePrincipal principal) {return snapshot(device(principal));}

    public Manifest snapshot(Robot robot) {
        var context=navigationSessions.current(robot.getRobotId());
        FacilityMap map=context==null?robot.getCurrentMap():context.getMap();
        if(map==null || map.getStatus()!=MapStatus.ACTIVE || map.getAssetHash()==null || !map.getAssetHash().matches("[a-fA-F0-9]{64}")
                || map.getFrameId()==null || map.getFrameId().isBlank()) throw new BusinessRuleException("Navigation profile requires an ACTIVE map, frame and SHA-256 asset hash");
        ObjectNode root=wire.object().put("map_id",map.getMapId().toString()).put("map_asset_sha256",map.getAssetHash().toLowerCase(Locale.ROOT))
                .put("frame_id",map.getFrameId()).put("map_version",map.getVersionNo());
        ObjectNode targets=root.putObject("endpoints");
        if(context!=null)root.put("navigation_session_id",context.getSessionId().toString()).put("initialization_revision",context.getInitializationRevision());
        endpoints.findByMapMapIdOrderByEndpointCodeAsc(map.getMapId()).stream().filter(e->e.isActive()&&e.isAccessible())
                .sorted(Comparator.comparing(e->e.getEndpointId().toString())).forEach(e->targets.set(e.getEndpointId().toString(),wire.object()
                        .put("map_id",map.getMapId().toString()).put("x_m",number(e.getXM())).put("y_m",number(e.getYM())).put("yaw_rad",number(e.getYawRad()))));
        return new Manifest("BE-NAV-PROFILE-1",robot.getRobotId(),wire.canonicalHash(root),root);
    }

    @Transactional
    public Receipt confirm(Confirm request,DevicePrincipal principal) {
        List<Job> active=jobs.lockRobotJobs(principal.robotId(),HELD);
        Robot robot=robots.findByIdForUpdate(principal.robotId()).orElseThrow();requireDevice(robot,principal);
        return confirmLocked(request,robot,active);
    }

    @Transactional
    public Receipt confirmMqtt(UUID robotId,JsonNode ack) {
        if(!properties.isNavigationProfileSyncEnabled()) throw new BusinessRuleException("MQTT navigation sync disabled");
        List<Job> active=jobs.lockRobotJobs(robotId,HELD);
        Robot robot=robots.findByIdForUpdate(robotId).orElseThrow();
        UUID transport=UUID.fromString(ack.path("transportSessionId").asText());
        RobotMqttSession session=registry.requireCurrent(robotId,transport);
        if(!session.getProducerSessionId().toString().equals(ack.path("producerSessionId").asText()))
            throw new BusinessRuleException("Profile ACK producer differs from HELLO");
        if(!ack.path("accepted").asBoolean()) throw new BusinessRuleException("Pi rejected navigation profile: "+ack.path("errorCode").asText());
        return confirmLocked(new Confirm(transport,session.getProducerSessionId(),UUID.fromString(ack.path("mapId").asText()),
                ack.path("mapAssetHash").asText(),ack.path("profileHash").asText()),robot,active);
    }

    @Transactional(readOnly=true)
    public ObjectNode pendingMqtt(UUID robotId) {
        RobotMqttSession session=sessions.findById(robotId).orElse(null);
        if(!registry.fresh(session) || !"IDLE".equals(session.getHeartbeatJson().path("status").asText())
                || !session.getHeartbeatJson().path("activeJobStepId").isNull()
                || com.mediassistrover.backend.mapping.MappingLifecycleService.reportsOpen(session.getHeartbeatJson().path("mappingSession"))) return null;
        boolean move=false;for(JsonNode type:session.getHelloJson().path("enabledStepTypes")) if("MOVE".equals(type.asText())) move=true;
        if(!move) return null;
        Robot robot=robots.findById(robotId).orElseThrow();
        if(robot.getCurrentMap()==null && navigationSessions.current(robotId)==null) return null;
        Manifest expected=snapshot(robot);
        RobotNavigationProfile prior=profiles.findById(robotId).orElse(null);
        if(prior!=null && matches(prior,robot,session,expected.profileHash())) return null;
        if(expected.profile().path("endpoints").isEmpty() && navigationSessions.current(robotId)==null) return null;
        ObjectNode message=wire.object().put("messageType","NAV_PROFILE").put("robotId",robotId.toString())
                .put("transportSessionId",session.getTransportSessionId().toString()).put("profileVersion",expected.profileVersion())
                .put("profileHash",expected.profileHash());message.set("profile",expected.profile());return message;
    }

    private Receipt confirmLocked(Confirm request,Robot robot,List<Job> active) {
        if(!properties.manages(robot.getRobotId())) throw new BusinessRuleException("Profile ACK requires an MQTT-enrolled robot");
        RobotMqttSession session=registry.requireCurrent(robot.getRobotId(),request.transportSessionId());
        Instant now=Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(!session.isHandshakeAcked() || session.getLastHeartbeatAt()==null || !session.getLastHeartbeatAt().isAfter(now.minusSeconds(15))
                || !session.getProducerSessionId().equals(request.producerSessionId()) || session.getHeartbeatJson()==null)
            throw new BusinessRuleException("Profile ACK requires the current handshaken, fresh Pi session");
        RobotNavigationProfile prior=profiles.findById(robot.getRobotId()).orElse(null);
        if(prior!=null && matches(prior,robot,session,request.profileHash().toLowerCase(Locale.ROOT))
                && prior.getMapId().equals(request.mapId()) && prior.getMapAssetHash().equalsIgnoreCase(request.mapAssetHash())) return receipt(prior,true);
        if(!"IDLE".equals(session.getHeartbeatJson().path("status").asText()) || !session.getHeartbeatJson().path("activeJobStepId").isNull()
                || com.mediassistrover.backend.mapping.MappingLifecycleService.reportsOpen(session.getHeartbeatJson().path("mappingSession"))) throw new BusinessRuleException("New profile ACK requires an idle Pi with no active operation or mapping session");
        Manifest expected=snapshot(robot);
        if(!expected.profile().path("map_id").asText().equals(request.mapId().toString())
                || !expected.profile().path("map_asset_sha256").asText().equalsIgnoreCase(request.mapAssetHash())
                || !expected.profileHash().equalsIgnoreCase(request.profileHash())
                || !request.mapAssetHash().equalsIgnoreCase(Objects.toString(robot.getMapAssetHash(),"")))
            throw new BusinessRuleException("Pi profile/map/hash differs from the current BE catalog and heartbeat");
        if(commands.findByRobotIdAndStateIn(robot.getRobotId(),IN_FLIGHT).stream().anyMatch(c->c.getSendAttempts()>0
                && "MOVE".equals(c.getPayloadJson().path("step").path("stepType").asText())))
            throw new BusinessRuleException("Cannot replace a profile while a MOVE operation is unresolved");
        RobotNavigationProfile row=prior==null?new RobotNavigationProfile():prior;
        row.setRobotId(robot.getRobotId());row.setMapId(request.mapId());row.setMapAssetHash(request.mapAssetHash().toLowerCase(Locale.ROOT));
        row.setTransportSessionId(session.getTransportSessionId());row.setProducerSessionId(session.getProducerSessionId());
        row.setDeviceTokenVersion(robot.getDeviceTokenVersion());row.setProfileHash(expected.profileHash());row.setAcknowledgedAt(now);
        profiles.saveAndFlush(row);active.stream().filter(Job::isBeOrchestrated).forEach(work::request);
        return receipt(row,false);
    }

    /** Null holds a selected MOVE until the authenticated Pi has applied and acknowledged its profile. */
    public String ready(Robot robot,RobotMqttSession session,JsonNode params) {
        Manifest manifest=snapshot(robot);RobotNavigationProfile ack=profiles.findById(robot.getRobotId()).orElse(null);
        return navigationSessions.localized(robot.getRobotId(),session) && ack!=null && matches(ack,robot,session,manifest.profileHash())
                && params.path("map_id").asText().equals(ack.getMapId().toString())
                && manifest.profile().path("endpoints").has(params.path("endpoint_id").asText())?manifest.profileHash():null;
    }
    public boolean publicationReady(Robot robot,RobotMqttSession session,RobotCommandOutbox command) {
        RobotNavigationProfile ack=profiles.findById(robot.getRobotId()).orElse(null);
        if(command.getNavigationProfileHash()==null || ack==null || !matches(ack,robot,session,command.getNavigationProfileHash())) return false;
        if(!navigationSessions.localized(robot.getRobotId(),session)||!navigationSessions.commandMatches(robot.getRobotId(),command.getPayloadJson().path("navigationContext")))return false;
        // Before first publication, catalog edits require a new operation, never mutation of the frozen command.
        return command.getSendAttempts()>0 || command.getNavigationProfileHash().equals(snapshot(robot).profileHash());
    }
    private boolean matches(RobotNavigationProfile ack,Robot robot,RobotMqttSession session,String hash) {
        return session!=null && ack.getTransportSessionId().equals(session.getTransportSessionId()) && ack.getProducerSessionId().equals(session.getProducerSessionId())
                && ack.getDeviceTokenVersion()==robot.getDeviceTokenVersion() && ack.getProfileHash().equals(hash) && robot.getCurrentMap()!=null
                && ack.getMapId().equals(robot.getCurrentMap().getMapId()) && ack.getMapAssetHash().equalsIgnoreCase(Objects.toString(robot.getMapAssetHash(),""));
    }
    @Transactional(readOnly=true)
    public JsonNode zoneConfiguration(UUID mapId,DevicePrincipal principal) {
        Robot robot=device(principal);
        if(robot.getCurrentMap()==null || !robot.getCurrentMap().getMapId().equals(mapId)) throw new AccessDeniedException("Device can only read zones of its assigned map");
        FacilityMap map=robot.getCurrentMap();ObjectNode config=wire.object().put("map_id",mapId.toString()).put("frame_id",map.getFrameId()).put("map_version",map.getVersionNo()).put("map_asset_sha256",map.getAssetHash());
        var list=config.putArray("zones");
        zones.findByMapMapIdOrderByZoneCodeAsc(mapId).stream().filter(Zone::isActive).forEach(z->{ObjectNode value=list.addObject().put("zone_id",z.getZoneId().toString())
                .put("map_id",mapId.toString()).put("zone_code",z.getZoneCode()).put("zone_type",z.getZoneType().name());value.set("polygon",z.getPolygonJson().deepCopy());
            value.put("capacity_limit",z.getCapacityLimit());value.put("calibrated_area_m2",z.getCalibratedAreaM2());value.put("camera_source_ref",z.getCameraSourceRef());
            value.set("camera_roi",z.getCameraRoiJson()==null?wire.object().nullNode():z.getCameraRoiJson().deepCopy());});
        return wire.object().put("configVersion","BE-ZONE-CONFIG-1").put("configHash",wire.canonicalHash(config)).set("config",config);
    }
    private Robot device(DevicePrincipal principal) {Robot robot=robots.findById(principal.robotId()).orElseThrow();requireDevice(robot,principal);return robot;}
    private void requireDevice(Robot robot,DevicePrincipal principal) {
        if(!robot.getRobotCode().equals(principal.robotCode()) || robot.getDeviceTokenVersion()!=principal.tokenVersion()
                || principal.expiresAt()==null || !principal.expiresAt().isAfter(Instant.now(clock))) throw new AccessDeniedException("Current device session required");
    }
    private BigDecimal number(BigDecimal value) {return value.signum()==0?BigDecimal.ZERO:value.stripTrailingZeros();}
    private Receipt receipt(RobotNavigationProfile row,boolean duplicate) {return new Receipt(row.getRobotId(),row.getTransportSessionId(),row.getProfileHash(),duplicate,row.getAcknowledgedAt());}
}
