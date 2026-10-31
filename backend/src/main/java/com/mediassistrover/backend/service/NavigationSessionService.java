package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapping.MapBundleService;
import com.mediassistrover.backend.mqtt.MqttWire;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class NavigationSessionService {
    private final RobotNavigationSessionRepository rows;
    private final RobotRepository robots;
    private final FacilityMapRepository maps;
    private final RobotMqttSessionRepository links;
    private final RobotNavigationProfileRepository profiles;
    private final JobRepository jobs;
    private final MapBundleService bundles;
    private final MqttWire wire;
    private final Clock clock;
    private static final List<JobStatus> HELD=List.of(JobStatus.QUEUED,JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED);
    @Schema(name="NavigationSessionCreateRequest") public record Create(@NotNull UUID mapId,@Min(60) @Max(28800) int durationSec) { }
    public record Initialize(@NotNull UUID sessionId,@NotBlank @Pattern(regexp="SEED|GLOBAL") String mode,
                             Double xM,Double yM,Double yawRad) { }
    public record Close(@NotNull UUID sessionId) { }

    public RobotNavigationSession current(UUID robotId){return rows.findById(robotId).filter(r->r.open(Instant.now(clock))).orElse(null);}
    @Transactional
    public ObjectNode create(UUID robotId,Create request){
        idle(robotId);Robot robot=robot(robotId);
        if(current(robotId)!=null)throw new BusinessRuleException("Close the existing navigation session before selecting another map");
        var map=maps.findById(request.mapId()).orElseThrow(()->new ResourceNotFoundException("Map not found"));
        if(map.getStatus()!=MapStatus.ACTIVE)throw new BusinessRuleException("Activate the saved map first");
        if(!bundles.navigationSafe(map.getMapId()))throw new BusinessRuleException("MAP_UNKNOWN_RELOAD_UNSAFE: create a navigation copy before starting this map");
        RobotNavigationSession row=new RobotNavigationSession();row.setRobotId(robotId);row.setSessionId(UUID.randomUUID());row.setMap(map);
        row.setMapAssetHash(map.getAssetHash().toLowerCase(Locale.ROOT));row.setCreatedAt(Instant.now(clock));
        row.setExpiresAt(row.getCreatedAt().plusSeconds(request.durationSec()));row.setInitializationMode("MANUAL");row.setInitializationRevision(0);
        rows.saveAndFlush(row);profiles.deleteById(robotId);
        return describe(row);
    }
    @Transactional(readOnly=true)
    public ObjectNode status(UUID robotId){if(!robots.existsById(robotId))throw new ResourceNotFoundException("Robot not found");var row=rows.findById(robotId).orElse(null);return row==null?wire.object().put("state","NONE"):describe(row);}
    @Transactional(readOnly=true)
    public ObjectNode device(DevicePrincipal principal){requireDevice(principal);var row=current(principal.robotId());
        if(row==null)throw new BusinessRuleException("NAVIGATION_SESSION_CLOSED_OR_EXPIRED");return describe(row);}
    @Transactional
    public ObjectNode initialize(UUID robotId,Initialize request){
        idle(robotId);var row=required(robotId,request.sessionId());
        if("SEED".equals(request.mode())) {
            if(request.xM()==null||request.yM()==null||request.yawRad()==null||!Double.isFinite(request.xM())||!Double.isFinite(request.yM())
                    ||!Double.isFinite(request.yawRad())||Math.abs(request.yawRad())>Math.PI)throw new BusinessRuleException("SEED requires finite xM/yM and yawRad within +/-pi");
            bundles.requireFreePoint(row.getMap().getMapId(),request.xM(),request.yM());
            row.setInitialPoseJson(wire.object().put("x_m",request.xM()).put("y_m",request.yM()).put("yaw_rad",request.yawRad()));
        } else {
            if(request.xM()!=null||request.yM()!=null||request.yawRad()!=null)throw new BusinessRuleException("GLOBAL does not accept a guessed pose");
            row.setInitialPoseJson(null);
        }
        row.setInitializationMode(request.mode());row.setInitializationRevision(row.getInitializationRevision()+1);
        profiles.deleteById(robotId);return describe(row);
    }
    @Transactional
    public ObjectNode close(UUID robotId,UUID id){idle(robotId);var row=rows.findById(robotId).orElseThrow();if(!row.getSessionId().equals(id))throw new BusinessRuleException("Navigation session changed");
        if(row.getClosedAt()==null)row.setClosedAt(Instant.now(clock));profiles.deleteById(robotId);return describe(row);}
    @Transactional
    public ObjectNode closeDevice(DevicePrincipal device,UUID id){requireDevice(device);return close(device.robotId(),id);}

    /** Called after the caller has locked Robot. Never accepts a context from another process. */
    public void hello(UUID robotId,UUID producer,JsonNode context){
        var row=current(robotId);
        if(context==null||context.isNull()||context.isMissingNode()) {
            // Returning to ordinary mapping explicitly ends a previous temporary navigation context.
            if(row!=null && row.getProducerSessionId()!=null)row.setClosedAt(Instant.now(clock));return;
        }
        if(row==null||!matches(row,context))throw new BusinessRuleException("NAVIGATION_SESSION_MAP_OR_ID_MISMATCH");
        if(row.getProducerSessionId()!=null&&!row.getProducerSessionId().equals(producer))throw new BusinessRuleException("NAVIGATION_SESSION_PROCESS_CHANGED: open a new session after restarting Pi navigation");
        row.setProducerSessionId(producer);
    }
    public boolean matches(RobotNavigationSession row,JsonNode context){return context!=null&&row.getSessionId().toString().equals(context.path("session_id").asText())
            &&row.getMap().getMapId().toString().equals(context.path("map_id").asText())&&row.getMapAssetHash().equals(context.path("map_asset_hash").asText());}
    public boolean peer(UUID robotId,RobotMqttSession link){var row=current(robotId);return row!=null&&link!=null
            &&Objects.equals(row.getProducerSessionId(),link.getProducerSessionId())&&matches(row,link.getHelloJson().path("navigationSession"));}
    public boolean localized(UUID robotId,RobotMqttSession link){
        if(link==null||link.getHelloJson()==null)return false;
        var row=current(robotId);if(row==null)return !link.getHelloJson().hasNonNull("navigationSession");
        if(!peer(robotId,link)||link.getHeartbeatJson()==null)return false;
        JsonNode l=link.getHeartbeatJson().path("telemetry").path("localization"),pose=link.getHeartbeatJson().path("telemetry").path("map_pose");
        return l.path("ready").asBoolean()&&l.path("session_id").asText().equals(row.getSessionId().toString())
                &&l.path("age_sec").asDouble(99)<=.75&&pose.path("age_sec").asDouble(99)<=1.
                &&l.path("initialization_revision").asLong(-1)==row.getInitializationRevision()&&row.getInitializationRevision()>0
                &&l.path("map_id").asText().equals(row.getMap().getMapId().toString())&&l.path("map_asset_hash").asText().equals(row.getMapAssetHash())
                &&pose.path("fresh").asBoolean()&&pose.path("value").path("navigation_session_id").asText().equals(row.getSessionId().toString())
                &&pose.path("value").path("initialization_revision").asLong(-1)==row.getInitializationRevision();
    }
    public ObjectNode commandContext(UUID robotId,String profileHash){
        var row=current(robotId);if(row==null)return null;
        return wire.object().put("session_id",row.getSessionId().toString()).put("map_id",row.getMap().getMapId().toString())
                .put("map_asset_hash",row.getMapAssetHash()).put("initialization_revision",row.getInitializationRevision()).put("profile_hash",profileHash);
    }
    public void requireReadyContext(UUID robotId,UUID id,UUID mapId){
        var row=required(robotId,id);var link=links.findById(robotId).orElse(null);Instant now=Instant.now(clock);
        if(!row.getMap().getMapId().equals(mapId)||link==null||link.isClosed()||!link.isDispatchReady()
                ||link.getLastHeartbeatAt()==null||!link.getLastHeartbeatAt().isAfter(now.minusSeconds(5))||!localized(robotId,link))
            throw new BusinessRuleException("NAVIGATION_SESSION_NOT_LOCALIZED: wait for a verified fresh robot pose in this map");
    }
    public boolean commandMatches(UUID robotId,JsonNode context){var row=current(robotId);
        return row==null?context==null||context.isMissingNode()||context.isNull():matches(row,context)&&context.path("initialization_revision").asLong(-1)==row.getInitializationRevision();}
    private ObjectNode describe(RobotNavigationSession row){
        Instant now=Instant.now(clock);var link=links.findById(row.getRobotId()).orElse(null);
        boolean fresh=link!=null&&!link.isClosed()&&link.getLastHeartbeatAt()!=null&&link.getLastHeartbeatAt().isAfter(now.minusSeconds(5));
        String state=!row.open(now)?row.getClosedAt()!=null?"CLOSED":"EXPIRED":!fresh||!peer(row.getRobotId(),link)?"WAITING_FOR_PI":localized(row.getRobotId(),link)?"READY":"LOCALIZING";
        ObjectNode out=wire.object().put("sessionId",row.getSessionId().toString()).put("robotId",row.getRobotId().toString())
                .put("mapId",row.getMap().getMapId().toString()).put("mapAssetHash",row.getMapAssetHash()).put("frameId",row.getMap().getFrameId())
                .put("state",state).put("expiresAt",row.getExpiresAt().toString()).put("observedAt",now.toString());
        out.putObject("initialization").put("mode",row.getInitializationMode()).put("revision",row.getInitializationRevision())
                .set("pose",row.getInitialPoseJson()==null?wire.object().nullNode():row.getInitialPoseJson());
        if(fresh&&peer(row.getRobotId(),link))out.set("localization",link.getHeartbeatJson().path("telemetry").path("localization"));
        return out;
    }
    private RobotNavigationSession required(UUID robotId,UUID id){var row=current(robotId);if(row==null||!row.getSessionId().equals(id))throw new BusinessRuleException("NAVIGATION_SESSION_CLOSED_OR_CHANGED");return row;}
    private Robot robot(UUID id){return robots.findByIdForUpdate(id).orElseThrow(()->new ResourceNotFoundException("Robot not found"));}
    private void idle(UUID id){if(!jobs.lockRobotJobs(id,HELD).isEmpty())throw new BusinessRuleException("End/cancel the robot's current Job before changing navigation context or initial pose");
        var robot=robot(id);if(robot.getStatus()==RobotStatus.BUSY)throw new BusinessRuleException("Stop manual movement first");}
    private void requireDevice(DevicePrincipal p){if(p==null)throw new org.springframework.security.access.AccessDeniedException("DEVICE identity required");var r=robots.findById(p.robotId()).orElseThrow();if(r.getDeviceTokenVersion()!=p.tokenVersion()||!r.getRobotCode().equals(p.robotCode())||p.expiresAt()==null||!p.expiresAt().isAfter(Instant.now(clock)))throw new org.springframework.security.access.AccessDeniedException("Current DEVICE token required");}
}
