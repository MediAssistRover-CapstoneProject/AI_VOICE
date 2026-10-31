package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.config.OperationsProperties;
import com.mediassistrover.backend.dto.robot.request.RobotRequests.*;
import com.mediassistrover.backend.dto.robot.response.RobotResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.RobotMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RobotServiceImpl implements RobotService {
    private final RobotRepository robotRepository;
    private final RobotStatusHistoryRepository statusHistoryRepository;
    private final RobotLocationHistoryRepository locationHistoryRepository;
    private final FacilityMapRepository mapRepository;
    private final AccountRepository accountRepository;
    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final RobotMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final OperationsProperties operationsProperties;
    private final RuntimeSettingService runtimeSettingService;
    private final Clock clock;
    private final StaffOperationalScopePolicy staffScope;
    private final org.springframework.context.ApplicationEventPublisher applicationEvents;
    private final SecureRandom secureRandom=new SecureRandom();

    @Override @Transactional public RobotResponse create(RobotCreateRequest r,UUID actorId,RequestMetadata md){
        if(robotRepository.existsByRobotCodeIgnoreCase(r.robotCode()))throw new ConflictException("Robot code already exists");
        if(r.serialNumber()!=null&&robotRepository.existsBySerialNumber(r.serialNumber()))throw new ConflictException("Serial number already exists");
        Robot robot=mapper.toEntity(r);robot.setCurrentMap(map(r.currentMapId()));robot.setBatteryUnit(BatteryUnit.UNKNOWN);robot=robotRepository.save(robot);
        recordStatus(robot,null,RobotStatus.OFFLINE,"ROBOT_REGISTERED",null,UUID.randomUUID());
        audit(account(actorId),"ROBOT_CREATED",robot.getRobotId(),md);return mapper.toResponse(robot);
    }
    @Override @Transactional public RobotResponse update(UUID id,RobotUpdateRequest r,UUID actorId,RequestMetadata md){Robot robot=robot(id);
        if(r.serialNumber()!=null&&!Objects.equals(robot.getSerialNumber(),r.serialNumber())&&robotRepository.existsBySerialNumber(r.serialNumber()))throw new ConflictException("Serial number already exists");
        mapper.update(r,robot);robot.setCurrentMap(map(r.currentMapId()));audit(account(actorId),"ROBOT_UPDATED",id,md);return mapper.toResponse(robot);}
    @Override public RobotResponse get(UUID id){return mapper.toResponse(robot(id));}
    @Override public Page<RobotResponse> list(RobotStatus status,Pageable p){return(status==null?robotRepository.findAll(p):robotRepository.findByStatus(status,p)).map(mapper::toResponse);}
    @Override @Transactional public DeviceSecretResponse provisionSecret(UUID id,UUID actorId,RequestMetadata md){Robot robot=robot(id);if(robot.getDeviceSecretHash()!=null)throw new ConflictException("Robot already has a provisioned device secret; use rotate-secret");return issueSecret(robot,"ROBOT_DEVICE_SECRET_PROVISIONED",actorId,md);}
    @Override @Transactional public DeviceSecretResponse rotateSecret(UUID id,UUID actorId,RequestMetadata md){Robot robot=robot(id);robot.setDeviceTokenVersion(robot.getDeviceTokenVersion()+1);return issueSecret(robot,"ROBOT_DEVICE_SECRET_ROTATED",actorId,md);}
    @Override @Transactional public RobotResponse maintenance(UUID id,String reason,UUID actorId,RequestMetadata md){Robot robot=robot(id);if(!Set.of(RobotStatus.IDLE,RobotStatus.BUSY).contains(robot.getStatus()))throw new BusinessRuleException("Only IDLE or BUSY robots can enter maintenance");transition(robot,RobotStatus.MAINTENANCE,reason,null,UUID.randomUUID());audit(account(actorId),"ROBOT_MAINTENANCE",id,md);return mapper.toResponse(robot);}
    @Override @Transactional public RobotResponse reconcile(UUID id,String reason,AccountPrincipal actor,RequestMetadata md){Robot robot=robot(id);staffScope.requireRobotRecoveryAccess(id,actor);if(!Set.of(RobotStatus.OFFLINE,RobotStatus.ERROR,RobotStatus.EMERGENCY_STOP,RobotStatus.MAINTENANCE).contains(robot.getStatus()))throw new BusinessRuleException("Robot does not require reconciliation");transition(robot,RobotStatus.STARTING,reason,null,UUID.randomUUID());audit(account(actor.accountId()),"ROBOT_RECONCILED",id,md);return mapper.toResponse(robot);}
    @Override public Page<RobotStatusHistoryResponse> statusHistory(UUID id,Pageable p){robot(id);return statusHistoryRepository.findByRobotRobotId(id,p).map(mapper::toResponse);}
    @Override public Page<RobotLocationResponse> locationHistory(UUID id,Instant from,Instant to,Pageable p){robot(id);if(from!=null&&to!=null&&!from.isBefore(to))throw new BusinessRuleException("from must be before to");return locationHistoryRepository.search(id,from,to,p).map(mapper::toResponse);}
    @Override @Transactional public int markHeartbeatTimeoutsOffline(){Instant now=Instant.now(clock);long seconds=runtimeSettingService.getLong("operations.robot_offline_threshold_seconds",operationsProperties.robotOfflineThresholdSeconds(),10,3600);Instant threshold=now.minusSeconds(seconds);List<Robot> stale=robotRepository.findByStatusNotAndLastHeartbeatAtLessThanEqual(RobotStatus.OFFLINE,threshold);for(Robot r:stale){List<Job> jobs=jobRepository.lockRobotJobs(r.getRobotId(),List.of(JobStatus.ACCEPTED,JobStatus.RUNNING));for(Job j:jobs){String old=j.getStatus().name();j.setStatus(JobStatus.RECOVERY_REQUIRED);j.setErrorCode("ROBOT_HEARTBEAT_TIMEOUT");j.setFailureReason("Robot went offline; manual reconciliation is required");if(j.getServiceRequest()!=null)j.getServiceRequest().setStatus(ServiceRequestStatus.RECOVERY_REQUIRED);jobEventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(j).robot(r).producerType(JobEventProducerType.SCHEDULER).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType("ROBOT_OFFLINE").previousStatus(old).newStatus(JobStatus.RECOVERY_REQUIRED.name()).statusScope("JOB").eventDataJson(new com.fasterxml.jackson.databind.node.ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance).put("reason","HEARTBEAT_TIMEOUT")).occurredAt(now).receivedAt(now).persistedAt(now).build());}transition(r,RobotStatus.OFFLINE,"HEARTBEAT_TIMEOUT",null,UUID.randomUUID());applicationEvents.publishEvent(new RobotRealtimeEventStream.Changed(r.getRobotId()));}return stale.size();}

    private DeviceSecretResponse issueSecret(Robot robot,String action,UUID actorId,RequestMetadata md){byte[] bytes=new byte[32];secureRandom.nextBytes(bytes);String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant now=Instant.now(clock);robot.setDeviceSecretHash(passwordEncoder.encode(secret));robot.setDeviceSecretRotatedAt(now);audit(account(actorId),action,robot.getRobotId(),md);return new DeviceSecretResponse(robot.getRobotId(),robot.getRobotCode(),secret,now);}
    private void transition(Robot r,RobotStatus next,String reason,String errorCode,UUID eventId){RobotStatus prev=r.getStatus();if(prev==next)return;r.setStatus(next);recordStatus(r,prev,next,reason,errorCode,eventId);}
    private void recordStatus(Robot robot,RobotStatus previous,RobotStatus next,String reason,String errorCode,UUID id){statusHistoryRepository.save(RobotStatusHistory.builder().robotStatusHistoryId(id).robot(robot).previousStatus(previous).newStatus(next).reason(reason).errorCode(errorCode).recordedAt(Instant.now(clock)).build());}
    private FacilityMap map(UUID id){if(id==null)return null;FacilityMap map=mapRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Map not found: "+id));if(map.getStatus()!=MapStatus.ACTIVE)throw new BusinessRuleException("Robot current map must be ACTIVE");return map;}
    private Robot robot(UUID id){return robotRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Robot not found: "+id));}
    private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Account not found: "+id));}
    private void audit(Account a,String action,UUID id,RequestMetadata md){auditService.record(a,action,"Robot",id,null,Map.of("action",action),md);}
}
