package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.response.PiResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.PiIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PiIntegrationServiceImpl implements PiIntegrationService {
    private final RobotRepository robotRepository;
    private final RobotStatusHistoryRepository statusRepository;
    private final RobotLocationHistoryRepository locationRepository;
    private final FacilityMapRepository mapRepository;
    private final EndpointRepository endpointRepository;
    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final Clock clock;
    private final com.mediassistrover.backend.mqtt.MqttProperties mqtt;
    private final com.mediassistrover.backend.mqtt.MqttRolloutService rollout;
    private final org.springframework.context.ApplicationEventPublisher applicationEvents;

    @Override @Transactional
    public AckResponse heartbeat(PiEnvelope<HeartbeatPayload> e,DevicePrincipal principal){validate(e,principal,"HEARTBEAT");Robot robot=robot(principal.robotId());Instant now=Instant.now(clock);
        if(statusRepository.existsById(e.messageId()))return ack(e,true,robot,false,false,null);
        HeartbeatPayload p=e.payload();if(p.protocolMajor()!=1)throw new BusinessRuleException("Unsupported protocol major version");
        FacilityMap current=p.currentMapId()==null?robot.getCurrentMap():facilityMap(p.currentMapId());RobotStatus previous=robot.getStatus();boolean rebootRecovery=previous==RobotStatus.BUSY&&p.status()==RobotStatus.STARTING;RobotStatus acceptedStatus=rebootRecovery?RobotStatus.ERROR:p.status();if(rebootRecovery)recoverJobsAfterReboot(robot,now);else validateTransition(previous,acceptedStatus);
        robot.setCurrentMap(current);robot.setRuntimeVersion(p.runtimeVersion());robot.setFirmwareVersion(p.firmwareVersion());robot.setProtocolVersion(p.protocolMajor());robot.setCapabilitiesJson(p.capabilities());robot.setDeployedCatalogHash(normalizeHash(p.deployedCatalogHash()));robot.setMapAssetHash(normalizeHash(p.mapAssetHash()));robot.setStatus(acceptedStatus);robot.setLastHeartbeatAt(now);
        if(p.batteryCapturedAt()!=null&&(robot.getBatteryCapturedAt()==null||!p.batteryCapturedAt().isBefore(robot.getBatteryCapturedAt()))){robot.setBatteryRaw(p.batteryRaw());robot.setBatteryUnit(p.batteryUnit());robot.setBatteryCapturedAt(p.batteryCapturedAt());}
        statusRepository.save(RobotStatusHistory.builder().robotStatusHistoryId(e.messageId()).robot(robot).job(rebootRecovery?null:job(e.jobId(),robot)).previousStatus(previous).newStatus(acceptedStatus).reason(rebootRecovery?"DEVICE_RESTART_RECOVERY_REQUIRED":previous==acceptedStatus?"HEARTBEAT":"HEARTBEAT_STATUS_CHANGE").errorCode(rebootRecovery?"DEVICE_RESTART":p.errorCode()).recordedAt(now).build());
        applicationEvents.publishEvent(new com.mediassistrover.backend.service.RobotRealtimeEventStream.Changed(robot.getRobotId()));
        String expectedCatalog=expectedCatalogHash(current);boolean catalogSync=current!=null&&!Objects.equals(expectedCatalog,robot.getDeployedCatalogHash());boolean mapSync=current!=null&&!Objects.equals(normalizeHash(current.getAssetHash()),robot.getMapAssetHash());
        return ack(e,false,robot,catalogSync,mapSync,null);
    }

    @Override @Transactional
    public AckResponse location(PiEnvelope<LocationPayload> e,DevicePrincipal principal){validate(e,principal,"ROBOT_LOCATION");Robot robot=robot(principal.robotId());if(locationRepository.existsById(e.messageId()))return ack(e,true,robot,false,false,null);LocationPayload p=e.payload();FacilityMap map=facilityMap(p.mapId());if(robot.getCurrentMap()==null||!robot.getCurrentMap().getMapId().equals(map.getMapId()))throw new BusinessRuleException("Location map does not match the robot current map");
        locationRepository.save(RobotLocationHistory.builder().robotLocationHistoryId(e.messageId()).robot(robot).map(map).job(job(e.jobId(),robot)).xM(p.xM()).yM(p.yM()).yawRad(p.yawRad()).localizationConfidence(p.localizationConfidence()).frameId("map").source("PI_LOCATION_EVENT").fresh(p.fresh()).capturedAt(p.capturedAt()).receivedAt(Instant.now(clock)).build());
        applicationEvents.publishEvent(new com.mediassistrover.backend.service.RobotRealtimeEventStream.Changed(robot.getRobotId()));
        return ack(e,false,robot,false,false,null);
    }

    @Override public CatalogManifestResponse catalogManifest(DevicePrincipal principal){Robot robot=robot(principal.robotId());FacilityMap map=robot.getCurrentMap();if(map==null)return new CatalogManifestResponse("1.0",robot.getRobotId(),null,null,null,null,robot.getDeployedCatalogHash(),null,false,false,List.of());List<Endpoint> endpoints=endpointRepository.findByMapMapIdOrderByEndpointCodeAsc(map.getMapId());String expected=hashEndpoints(endpoints);List<EndpointManifest> items=endpoints.stream().map(ep->new EndpointManifest(ep.getEndpointId(),ep.getEndpointCode(),ep.getEndpointType(),ep.getXM().toPlainString(),ep.getYM().toPlainString(),ep.getYawRad().toPlainString(),ep.isActive())).toList();return new CatalogManifestResponse("1.0",robot.getRobotId(),map.getMapId(),map.getVersionNo(),map.getAssetHash(),map.getMapFileUri(),robot.getDeployedCatalogHash(),expected,!Objects.equals(normalizeHash(robot.getDeployedCatalogHash()),expected),!Objects.equals(normalizeHash(robot.getMapAssetHash()),normalizeHash(map.getAssetHash())),items);}
    @Override public PiMapMetadata mapMetadata(UUID mapId,DevicePrincipal principal){Robot robot=robot(principal.robotId());if(robot.getCurrentMap()==null||!robot.getCurrentMap().getMapId().equals(mapId))throw new BusinessRuleException("Device can only access its assigned map");FacilityMap map=facilityMap(mapId);return new PiMapMetadata(map.getMapId(),map.getMapCode(),map.getVersionNo(),map.getMapFileUri(),map.getAssetHash(),map.getFrameId(),map.getStatus());}

    private void validate(PiEnvelope<?> e,DevicePrincipal p,String type){rollout.requireLegacyExecution();if(mqtt.manages(p.robotId()))throw new BusinessRuleException("MQTT-enrolled robots must send heartbeat/location through MQTT; REST catalog remains available");if(!"1.0".equals(e.protocolVersion()))throw new BusinessRuleException("Unsupported protocolVersion");if(!p.robotId().equals(e.robotId()))throw new BusinessRuleException("Envelope robotId does not match device token");if(!type.equals(e.messageType()))throw new BusinessRuleException("Unexpected messageType; expected "+type);if(e.occurredAt().isAfter(Instant.now(clock).plusSeconds(300)))throw new BusinessRuleException("occurredAt is too far in the future");}
    private void validateTransition(RobotStatus from,RobotStatus to){if(from==to)return;boolean allowed=switch(from){case OFFLINE->to==RobotStatus.STARTING;case STARTING->Set.of(RobotStatus.IDLE,RobotStatus.ERROR,RobotStatus.EMERGENCY_STOP,RobotStatus.OFFLINE).contains(to);case IDLE->Set.of(RobotStatus.STARTING,RobotStatus.BUSY,RobotStatus.ERROR,RobotStatus.EMERGENCY_STOP,RobotStatus.OFFLINE).contains(to);case BUSY->Set.of(RobotStatus.IDLE,RobotStatus.ERROR,RobotStatus.EMERGENCY_STOP,RobotStatus.OFFLINE).contains(to);case ERROR,EMERGENCY_STOP,MAINTENANCE->to==RobotStatus.STARTING||to==RobotStatus.OFFLINE;};if(!allowed)throw new BusinessRuleException("Invalid robot status transition: "+from+" -> "+to);}
    private void recoverJobsAfterReboot(Robot robot,Instant now){for(Job active:jobRepository.findByRobotRobotIdAndStatusIn(robot.getRobotId(),List.of(JobStatus.ACCEPTED,JobStatus.RUNNING))){String previous=active.getStatus().name();active.setStatus(JobStatus.RECOVERY_REQUIRED);active.setErrorCode("DEVICE_RESTART");active.setFailureReason("Raspberry Pi restarted while the Job was active; manual reconciliation is required");if(active.getServiceRequest()!=null)active.getServiceRequest().setStatus(ServiceRequestStatus.RECOVERY_REQUIRED);jobEventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(active).robot(robot).producerType(JobEventProducerType.ROBOT).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType("DEVICE_RESTART").previousStatus(previous).newStatus(JobStatus.RECOVERY_REQUIRED.name()).statusScope("JOB").eventDataJson(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("reason","DEVICE_RESTART")).occurredAt(now).receivedAt(now).persistedAt(now).build());}}
    private AckResponse ack(PiEnvelope<?> e,boolean duplicate,Robot robot,boolean catalogSync,boolean mapSync,String reason){return new AckResponse(e.messageId(),e.operationKey(),e.correlationId(),true,duplicate,Instant.now(clock),robot.getStatus(),catalogSync,mapSync,reason);}
    private String expectedCatalogHash(FacilityMap map){return map==null?null:hashEndpoints(endpointRepository.findByMapMapIdOrderByEndpointCodeAsc(map.getMapId()));}
    private String hashEndpoints(List<Endpoint> endpoints){StringBuilder canonical=new StringBuilder();endpoints.forEach(e->canonical.append(e.getEndpointId()).append('|').append(e.getEndpointCode()).append('|').append(e.getEndpointType()).append('|').append(e.getXM()).append('|').append(e.getYM()).append('|').append(e.getYawRad()).append('|').append(e.isActive()).append('\n'));try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("SHA-256 unavailable",ex);}}
    private String normalizeHash(String hash){return hash==null?null:hash.toLowerCase(Locale.ROOT);}
    private Robot robot(UUID id){return robotRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Robot not found: "+id));}
    private FacilityMap facilityMap(UUID id){return mapRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Map not found: "+id));}
    private Job job(UUID id,Robot robot){if(id==null)return null;Job job=jobRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Job not found: "+id));if(job.getRobot()==null||!job.getRobot().getRobotId().equals(robot.getRobotId()))throw new BusinessRuleException("Job is not assigned to this robot");return job;}
}
