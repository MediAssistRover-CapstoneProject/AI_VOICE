package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.*;
import com.mediassistrover.backend.dto.request.response.ServiceRequestResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.RequestJobMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.DeviceResourceScopePolicy;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ServiceRequestServiceImpl implements ServiceRequestService {
    private static final Set<ServiceRequestType> OPERATIONAL_TYPES=EnumSet.of(
            ServiceRequestType.CROWD_MONITORING,ServiceRequestType.FALL_DETECTION_RESPONSE,
            ServiceRequestType.RESTRICTED_AREA_MONITORING,ServiceRequestType.AUTO_CHARGING,
            ServiceRequestType.NAVIGATION_RECOVERY,ServiceRequestType.ROBOT_STARTUP_STANDBY);
    private final ServiceRequestRepository requestRepository; private final NavigationRequestRepository navigationRepository;
    private final EscortRequestRepository escortRepository; private final FacilityMapRepository mapRepository;
    private final DeliveryRequestRepository deliveryRepository;
    private final OrchestrationWorkService wakeups;
    private final RoomRepository roomRepository; private final EndpointRepository endpointRepository;
    private final ZoneRepository zoneRepository; private final DetectionEventRepository detectionEventRepository;
    private final EndpointGroupRepository groupRepository; private final AccountRepository accountRepository;
    private final EndpointGroupMemberRepository memberRepository;
    private final WorkflowRepository workflowRepository;
    private final JobExecutionService jobExecutionService; private final RequestJobMapper mapper;
    private final JobRepository jobRepository;
    private final RobotRepository robotRepository;
    private final ObjectMapper objectMapper; private final AuditService auditService; private final Clock clock;
    private final DeviceResourceScopePolicy deviceScope;
    private final StaffOperationalScopePolicy staffScope;
    private final NavigationSessionService navigationSessions;

    @Override @Transactional public ServiceRequestResponse createInformation(InformationCreateRequest r,String key,AccountPrincipal actor,RequestMetadata md){return createInformation(r,key,new RequestActor(actor.accountId(),null),md);}
    private ServiceRequestResponse createInformation(InformationCreateRequest r,String key,RequestActor actor,RequestMetadata md){
        ServiceRequest existing=existing(key,actor);if(existing!=null){if(existing.getRequestType()!=ServiceRequestType.INFORMATION)throw new ConflictException("Idempotency-Key was used for another request type");requireInformationReplay(existing,r);return mapper.toResponse(existing);}
        ObjectNode data=data(r.data());if(!data.path("question").isTextual()||data.path("question").asText().isBlank())data.put("question",r.note());
        ServiceRequest request=saveBase(ServiceRequestType.INFORMATION,r.priority(),r.note(),data,key,actor);
        audit(actor,"SERVICE_REQUEST_CREATED",request,md);return mapper.toResponse(request);
    }
    @Override @Transactional public NavigationResponse createNavigation(NavigationCreateRequest r,String key,AccountPrincipal actor,RequestMetadata md){
        if(r.robotId()!=null||r.navigationSessionId()!=null){
            if(actor.role()!=Role.ADMIN)throw new org.springframework.security.access.AccessDeniedException("Only ADMIN can pin a navigation test session");
            if(r.robotId()==null||r.navigationSessionId()==null)throw new BusinessRuleException("robotId and navigationSessionId are required together");
        }
        return createNavigation(r,key,new RequestActor(actor.accountId(),r.robotId()),md);
    }
    private NavigationResponse createNavigation(NavigationCreateRequest r,String key,RequestActor actor,RequestMetadata md){
        ServiceRequest found=existing(key,actor);if(found!=null){if(found.getRequestType()!=ServiceRequestType.NAVIGATION)throw new ConflictException("Idempotency-Key was used for another request type");requireNavigationReplay(found,r);return navigation(found);}
        if(r.navigationSessionId()!=null)navigationSessions.requireReadyContext(actor.robotId(),r.navigationSessionId(),r.mapId());
        FacilityMap map=map(r.mapId());requireActiveMap(map);Workflow selectedWorkflow=navigationWorkflow(r.workflowId());Endpoint endpoint=endpoint(r.destinationEndpointId());EndpointGroup group=group(r.destinationGroupId());validateTarget(map,r.targetMode(),endpoint,group);Endpoint origin=endpoint(r.originEndpointId());validateOrigin(map,origin);
        Room requestedRoom=room(r.roomId());validateRoom(map,requestedRoom,"Navigation room");
        if(r.accessibilityNeed()!=null&&!r.accessibilityNeed().isBlank()&&endpoint!=null&&!endpoint.isAccessible())throw new BusinessRuleException("Destination endpoint is not accessible");
        ObjectNode data=data(r.data());data.put("map_id",map.getMapId().toString());data.put("target_mode",r.targetMode().name());
        data.remove("navigation_session_id");if(r.navigationSessionId()!=null)data.put("navigation_session_id",r.navigationSessionId().toString());
        // Endpoint identity is authoritative request data, never an extension supplied through client data.
        data.remove(List.of("workflow_id","origin_endpoint_id","endpoint_id","endpoint_group_id"));if(selectedWorkflow!=null)data.put("workflow_id",selectedWorkflow.getWorkflowId().toString());if(origin!=null)data.put("origin_endpoint_id",origin.getEndpointId().toString());if(endpoint!=null)data.put("endpoint_id",endpoint.getEndpointId().toString());if(group!=null)data.put("endpoint_group_id",group.getEndpointGroupId().toString());
        ServiceRequest parent=saveBase(ServiceRequestType.NAVIGATION,r.priority(),r.note(),data,key,actor);
        NavigationRequest child=navigationRepository.save(NavigationRequest.builder().serviceRequest(parent).map(map).workflow(selectedWorkflow).room(requestedRoom).originEndpoint(origin).destinationEndpoint(endpoint).destinationGroup(group).targetMode(r.targetMode()).navigationMode(r.navigationMode()).accessibilityNeed(r.accessibilityNeed()).passengerCount(r.passengerCount()).arrivalInstruction(r.arrivalInstruction()).build());
        audit(actor,"NAVIGATION_REQUEST_CREATED",parent,md);return navigation(child);
    }
    @Override @Transactional public EscortResponse createEscort(EscortCreateRequest r,String key,AccountPrincipal actor,RequestMetadata md){return createEscort(r,key,new RequestActor(actor.accountId(),null),md);}
    private EscortResponse createEscort(EscortCreateRequest r,String key,RequestActor actor,RequestMetadata md){
        ServiceRequest found=existing(key,actor);if(found!=null){if(found.getRequestType()!=ServiceRequestType.ESCORT)throw new ConflictException("Idempotency-Key was used for another request type");requireEscortReplay(found,r);return escort(found);}
        if(r.walkingSpeedMps()==null||r.walkingSpeedMps().compareTo(new java.math.BigDecimal("0.100"))<0||r.walkingSpeedMps().compareTo(new java.math.BigDecimal("0.300"))>0)throw new BusinessRuleException("walkingSpeedMps must be between 0.100 and 0.300");
        FacilityMap map=map(r.mapId());requireActiveMap(map);Endpoint destination=endpoint(r.destinationEndpointId());EndpointGroup group=group(r.destinationGroupId());validateTarget(map,r.targetMode(),destination,group);Endpoint origin=endpoint(r.originEndpointId());if(origin!=null&& !origin.getMap().getMapId().equals(map.getMapId()))throw new BusinessRuleException("Origin endpoint must belong to request map");if(origin!=null&&!origin.isActive())throw new BusinessRuleException("Origin endpoint is inactive");
        Room originRoom=room(r.originRoomId());Room destinationRoom=room(r.destinationRoomId());validateRoom(map,originRoom,"Origin room");validateRoom(map,destinationRoom,"Destination room");
        ObjectNode data=data(r.data());data.put("map_id",map.getMapId().toString());data.put("target_mode",r.targetMode().name());
        if(destination!=null)data.put("endpoint_id",destination.getEndpointId().toString());if(group!=null)data.put("endpoint_group_id",group.getEndpointGroupId().toString());
        data.put("patient_reference",r.patientReference());
        ServiceRequest parent=saveBase(ServiceRequestType.ESCORT,r.priority(),r.note(),data,key,actor);
        EscortRequest child=escortRepository.save(EscortRequest.builder().serviceRequest(parent).map(map).originRoom(originRoom).destinationRoom(destinationRoom).originEndpoint(origin).destinationEndpoint(destination).destinationGroup(group).targetMode(r.targetMode()).patientReference(r.patientReference()).escortMode(r.escortMode()).walkingSpeedMps(r.walkingSpeedMps()).confirmationTimeoutSec(r.confirmationTimeoutSec()).escortNote(r.escortNote()).build());
        audit(actor,"ESCORT_REQUEST_CREATED",parent,md);return escort(child);
    }
    @Override @Transactional public ServiceRequestResponse createOperational(OperationalCreateRequest r,String key,AccountPrincipal actor,RequestMetadata md){
        if(!OPERATIONAL_TYPES.contains(r.requestType()))throw new BusinessRuleException("requestType is not an operational workflow entry point");
        if(!r.data().isObject())throw new BusinessRuleException("Operational request data must be a JSON object");
        if(r.requestType()==ServiceRequestType.ROBOT_STARTUP_STANDBY&&actor.role()!=Role.ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("Only ADMIN can request startup readiness checks");
        ServiceRequest found=existing(key,new RequestActor(actor.accountId(),r.robotId()));
        if(found!=null){if(found.getRequestType()!=r.requestType())throw new ConflictException("Idempotency-Key was used for another request type");return mapper.toResponse(found);}
        Robot requestedRobot=robot(r.robotId());ObjectNode payload=data(r.data());validateOperational(r.requestType(),requestedRobot,payload);
        ServiceRequest request=saveBase(r.requestType(),r.priority(),r.note(),payload,key,new RequestActor(actor.accountId(),requestedRobot.getRobotId()));
        // Operational requests are already fully specified and enter the queue atomically.
        request.setStatus(ServiceRequestStatus.SUBMITTED);jobExecutionService.createForRequest(request);request.setStatus(ServiceRequestStatus.QUEUED);
        audit(new RequestActor(actor.accountId(),null),"OPERATIONAL_REQUEST_QUEUED",request,md);return mapper.toResponse(request);
    }
    @Override public ServiceRequestResponse get(UUID id,AccountPrincipal actor){ServiceRequest r=request(id);staffScope.requireServiceRequestAccess(r,actor);return mapper.toResponse(r);}
    @Override public Page<ServiceRequestResponse> list(ServiceRequestStatus status,ServiceRequestType requestType,UUID robotId,Instant from,Instant to,AccountPrincipal actor,Pageable p){validateWindow(from,to);staffScope.requireCurrentStaffOrAdmin(actor);if(actor.role()==Role.ADMIN)return requestRepository.search(status,requestType,robotId,from,to,p).map(mapper::toResponse);List<ServiceRequest> scoped=requestRepository.search(status,requestType,robotId,from,to,Pageable.unpaged(p.getSort())).getContent().stream().filter(r->staffScope.canAccessServiceRequest(r,actor)).toList();return page(scoped,p).map(mapper::toResponse);}
    @Override @Transactional public ServiceRequestResponse submit(UUID id,AccountPrincipal actor,RequestMetadata md){ServiceRequest r=request(id);staffScope.requireServiceRequestAccess(r,actor);if(r.getStatus()!=ServiceRequestStatus.DRAFT)throw new BusinessRuleException("Only DRAFT request can be submitted");validateSubtype(r);r.setStatus(ServiceRequestStatus.SUBMITTED);jobExecutionService.createForRequest(r);r.setStatus(ServiceRequestStatus.QUEUED);audit(new RequestActor(actor.accountId(),null),"SERVICE_REQUEST_SUBMITTED",r,md);return mapper.toResponse(r);}
    @Override @Transactional public ServiceRequestResponse cancel(UUID id,String reason,AccountPrincipal actor,RequestMetadata md){ServiceRequest r=request(id);staffScope.requireServiceRequestAccess(r,actor);if(r.getStatus()==ServiceRequestStatus.CANCELLED||r.getStatus()==ServiceRequestStatus.RECOVERY_REQUIRED)return mapper.toResponse(r);if(Set.of(ServiceRequestStatus.COMPLETED,ServiceRequestStatus.FAILED).contains(r.getStatus()))throw new BusinessRuleException("Request is already terminal");Instant now=Instant.now(clock);DeliveryRequest delivery=r.getRequestType()==ServiceRequestType.DELIVERY?deliveryRepository.findById(id).orElse(null):null;boolean physical=delivery!=null&&(delivery.getPickedUpAt()!=null||delivery.getPickupCompartmentConfirmedAt()!=null||delivery.getPickupPayloadConfirmedAt()!=null);for(Job candidate:jobRepository.findByServiceRequestServiceRequestId(id)){Job job=jobRepository.lockById(candidate.getJobId()).orElseThrow();if(physical&&!Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED).contains(job.getStatus())){job.setStatus(JobStatus.RECOVERY_REQUIRED);job.setErrorCode("DELIVERY_CANCEL_AFTER_PHYSICAL_ACTION");job.setFailureReason(reason);if(job.getRobot()!=null)job.getRobot().setStatus(RobotStatus.ERROR);}else if(!Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED,JobStatus.RECOVERY_REQUIRED).contains(job.getStatus())&&job.getCancelCommandId()==null){job.setCancelCommandId(UUID.randomUUID());job.setCancelRequestedAt(now);job.setCancelRequestedByAccount(account(actor.accountId()));}wakeups.request(job);}r.setStatus(physical?ServiceRequestStatus.RECOVERY_REQUIRED:ServiceRequestStatus.CANCELLED);if(!physical)r.setCancelledAt(now);auditService.record(account(actor.accountId()),physical?"DELIVERY_RECOVERY_REQUIRED":"SERVICE_REQUEST_CANCELLED","ServiceRequest",id,null,Map.of("reason",reason),md);return mapper.toResponse(r);}

    private ServiceRequest saveBase(ServiceRequestType type,int priority,String note,JsonNode data,String key,RequestActor actor){if(key==null||key.isBlank()||key.length()>150)throw new BusinessRuleException("Idempotency-Key header is required and must be at most 150 characters");Instant now=Instant.now(clock);return requestRepository.save(ServiceRequest.builder().requestedByAccount(actor.accountId()==null?null:account(actor.accountId())).requestedRobot(actor.robotId()==null?null:robot(actor.robotId())).requestCode("REQ-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT)).requestType(type).sourceType(actor.accountId()!=null?RequestSourceType.STAFF_UI:RequestSourceType.ROBOT_UI).priority(priority).status(ServiceRequestStatus.DRAFT).requestNote(note).requestDataJson(data==null?objectMapper.createObjectNode():data).idempotencyKey(key).requestedAt(now).build());}
    private ObjectNode data(JsonNode source){return source!=null&&source.isObject()?((ObjectNode)source.deepCopy()):objectMapper.createObjectNode();}
    private ServiceRequest existing(String key,RequestActor actor){if(key==null||key.isBlank())return null;ServiceRequest r=requestRepository.findByIdempotencyKey(key).orElse(null);if(r!=null&&(!Objects.equals(r.getRequestedByAccount()==null?null:r.getRequestedByAccount().getAccountId(),actor.accountId()) || !Objects.equals(r.getRequestedRobot()==null?null:r.getRequestedRobot().getRobotId(),actor.robotId())))throw new ConflictException("Idempotency-Key belongs to another account");return r;}
    private void validateSubtype(ServiceRequest r){boolean exists=switch(r.getRequestType()){case NAVIGATION->navigationRepository.existsById(r.getServiceRequestId());case ESCORT->escortRepository.existsById(r.getServiceRequestId());case DELIVERY->deliveryRepository.existsById(r.getServiceRequestId());case INFORMATION->true;case EMERGENCY_SOS->r.getRelatedIncidentId()!=null;case CROWD_MONITORING,FALL_DETECTION_RESPONSE,RESTRICTED_AREA_MONITORING,AUTO_CHARGING,NAVIGATION_RECOVERY,ROBOT_STARTUP_STANDBY->r.getRequestedRobot()!=null;};if(!exists)throw new BusinessRuleException("Request subtype is missing");}
    private void validateOperational(ServiceRequestType type,Robot robot,ObjectNode data){
        if(data.size()>64)throw new BusinessRuleException("Operational request data exceeds 64 fields");
        switch(type){
            case CROWD_MONITORING,RESTRICTED_AREA_MONITORING -> {
                FacilityMap map=requiredMap(data);
                Zone zone=zoneRepository.findById(requiredUuid(data,"zone_id")).orElseThrow(()->new ResourceNotFoundException("Zone not found"));
                Endpoint checkpoint=endpoint(requiredUuid(data,"checkpoint_id"));
                if(!zone.getMap().getMapId().equals(map.getMapId())||!checkpoint.getMap().getMapId().equals(map.getMapId()))
                    throw new BusinessRuleException("Zone and checkpoint must belong to the request map");
                if(!zone.isActive()||!checkpoint.isActive())throw new BusinessRuleException("Zone and checkpoint must be active");
                if(robot.getCurrentMap()==null||!robot.getCurrentMap().getMapId().equals(map.getMapId()))throw new BusinessRuleException("Robot is not assigned to the requested map");
            }
            case FALL_DETECTION_RESPONSE -> {
                DetectionEvent event=detectionEventRepository.findById(requiredUuid(data,"detection_event_id"))
                        .orElseThrow(()->new ResourceNotFoundException("DetectionEvent not found"));
                if(event.getEventType()!=DetectionEventType.SUSPECTED_FALL)throw new BusinessRuleException("Detection event is not a suspected fall");
                if(!event.getRobot().getRobotId().equals(robot.getRobotId()))throw new BusinessRuleException("Detection event belongs to another robot");
                if(event.getConfidenceScore()==null)throw new BusinessRuleException("Detection event has no confidence score");
                data.put("confidence",event.getConfidenceScore());
            }
            case AUTO_CHARGING -> {data.put("robot_id",robot.getRobotCode());if(!data.hasNonNull("charge_request_id"))data.put("charge_request_id",UUID.randomUUID().toString());}
            case NAVIGATION_RECOVERY -> {
                FacilityMap map=requiredMap(data);UUID endpointId=requiredUuid(data,"destination_endpoint_id");Endpoint destination=endpoint(endpointId);
                if(!destination.getMap().getMapId().equals(map.getMapId()))throw new BusinessRuleException("Destination must belong to request map");
                Job original=jobRepository.findById(requiredUuid(data,"recovery_of_job_id"))
                        .orElseThrow(()->new ResourceNotFoundException("Recovery source Job not found"));
                if(original.getRobot()==null||!original.getRobot().getRobotId().equals(robot.getRobotId()))throw new BusinessRuleException("Recovery source Job belongs to another robot");
                if(original.getReconciledAt()==null||!Set.of(JobStatus.FAILED,JobStatus.CANCELLED).contains(original.getStatus()))
                    throw new BusinessRuleException("Navigation recovery requires a reconciled terminal Job");
                if(robot.getStatus()!=RobotStatus.IDLE)throw new BusinessRuleException("Navigation recovery requires an IDLE reconciled robot");
                if(!data.hasNonNull("recovery_request_id"))data.put("recovery_request_id",UUID.randomUUID().toString());
            }
            case ROBOT_STARTUP_STANDBY -> {if(!data.isEmpty())throw new BusinessRuleException("Startup readiness input must be an empty object");}
            default -> throw new BusinessRuleException("Unsupported operational request type");
        }
    }
    private FacilityMap requiredMap(ObjectNode data){return map(requiredUuid(data,"map_id"));}
    private UUID requiredUuid(ObjectNode data,String field){String value=requiredText(data,field);try{return UUID.fromString(value);}catch(IllegalArgumentException ex){throw new BusinessRuleException(field+" must be a UUID");}}
    private String requiredText(ObjectNode data,String field){if(!data.path(field).isTextual()||data.path(field).asText().isBlank())throw new BusinessRuleException(field+" is required");return data.path(field).asText();}
    private void validateTarget(FacilityMap map,TargetMode mode,Endpoint endpoint,EndpointGroup group){
        if(mode==TargetMode.ENDPOINT&&(endpoint==null||group!=null)||mode==TargetMode.ENDPOINT_GROUP&&(group==null||endpoint!=null))throw new BusinessRuleException("Target mode requires exactly one matching destination");
        UUID targetMap=endpoint!=null?endpoint.getMap().getMapId():group.getMap().getMapId();if(!targetMap.equals(map.getMapId()))throw new BusinessRuleException("Destination must belong to request map");
        if(endpoint!=null&&!endpoint.isActive()||group!=null&&!group.isActive())throw new BusinessRuleException("Destination is inactive");
        if(group!=null&&memberRepository.findByEndpointGroupEndpointGroupIdOrderBySequenceNo(group.getEndpointGroupId()).stream().noneMatch(m->m.isActive()&&m.getEndpoint().isActive()&&m.getEndpoint().getMap().getMapId().equals(map.getMapId())))throw new BusinessRuleException("Destination group has no active endpoint on the request map");
    }
    private void validateOrigin(FacilityMap map,Endpoint origin){if(origin==null)return;if(!origin.getMap().getMapId().equals(map.getMapId()))throw new BusinessRuleException("Origin endpoint must belong to request map");if(!origin.isActive())throw new BusinessRuleException("Origin endpoint is inactive");}
    private void requireActiveMap(FacilityMap map){if(map.getStatus()!=MapStatus.ACTIVE)throw new BusinessRuleException("Request map must be ACTIVE");}
    private void validateRoom(FacilityMap map,Room room,String label){if(room==null)return;if(!room.isActive())throw new BusinessRuleException(label+" is inactive");if(!room.getFloor().getFloorId().equals(map.getFloor().getFloorId()))throw new BusinessRuleException(label+" must belong to the request map floor");}
    private void requireInformationReplay(ServiceRequest existing,InformationCreateRequest r){ObjectNode expected=data(r.data());if(!expected.path("question").isTextual()||expected.path("question").asText().isBlank())expected.put("question",r.note());if(existing.getPriority()!=r.priority()||!Objects.equals(existing.getRequestNote(),r.note())||!Objects.equals(replayData(existing),expected))replayConflict();}
    private void requireNavigationReplay(ServiceRequest existing,NavigationCreateRequest r){NavigationRequest n=navigationRepository.findById(existing.getServiceRequestId()).orElseThrow(()->new ConflictException("Navigation subtype is missing"));Workflow selectedWorkflow=navigationWorkflow(r.workflowId());ObjectNode expected=data(r.data());expected.remove("navigation_session_id");if(r.navigationSessionId()!=null)expected.put("navigation_session_id",r.navigationSessionId().toString());expected.put("map_id",r.mapId().toString());expected.put("target_mode",r.targetMode().name());expected.remove(List.of("workflow_id","origin_endpoint_id","endpoint_id","endpoint_group_id"));if(selectedWorkflow!=null)expected.put("workflow_id",selectedWorkflow.getWorkflowId().toString());if(r.originEndpointId()!=null)expected.put("origin_endpoint_id",r.originEndpointId().toString());if(r.destinationEndpointId()!=null)expected.put("endpoint_id",r.destinationEndpointId().toString());if(r.destinationGroupId()!=null)expected.put("endpoint_group_id",r.destinationGroupId().toString());if(existing.getPriority()!=r.priority()||!Objects.equals(existing.getRequestNote(),r.note())||!Objects.equals(replayData(existing),expected)||!Objects.equals(n.getMap().getMapId(),r.mapId())||!Objects.equals(id(n.getWorkflow()),r.workflowId())||!Objects.equals(id(n.getRoom()),r.roomId())||!Objects.equals(id(n.getOriginEndpoint()),r.originEndpointId())||!Objects.equals(id(n.getDestinationEndpoint()),r.destinationEndpointId())||!Objects.equals(gid(n.getDestinationGroup()),r.destinationGroupId())||n.getTargetMode()!=r.targetMode()||n.getNavigationMode()!=r.navigationMode()||!Objects.equals(n.getAccessibilityNeed(),r.accessibilityNeed())||n.getPassengerCount()!=r.passengerCount()||!Objects.equals(n.getArrivalInstruction(),r.arrivalInstruction()))replayConflict();}
    private void requireEscortReplay(ServiceRequest existing,EscortCreateRequest r){EscortRequest e=escortRepository.findById(existing.getServiceRequestId()).orElseThrow(()->new ConflictException("Escort subtype is missing"));ObjectNode expected=data(r.data());expected.put("map_id",r.mapId().toString());expected.put("target_mode",r.targetMode().name());if(r.destinationEndpointId()!=null)expected.put("endpoint_id",r.destinationEndpointId().toString());if(r.destinationGroupId()!=null)expected.put("endpoint_group_id",r.destinationGroupId().toString());expected.put("patient_reference",r.patientReference());if(existing.getPriority()!=r.priority()||!Objects.equals(existing.getRequestNote(),r.note())||!Objects.equals(replayData(existing),expected)||!Objects.equals(e.getMap().getMapId(),r.mapId())||!Objects.equals(id(e.getOriginRoom()),r.originRoomId())||!Objects.equals(id(e.getDestinationRoom()),r.destinationRoomId())||!Objects.equals(id(e.getOriginEndpoint()),r.originEndpointId())||!Objects.equals(id(e.getDestinationEndpoint()),r.destinationEndpointId())||!Objects.equals(gid(e.getDestinationGroup()),r.destinationGroupId())||e.getTargetMode()!=r.targetMode()||!Objects.equals(e.getPatientReference(),r.patientReference())||e.getEscortMode()!=r.escortMode()||!sameDecimal(e.getWalkingSpeedMps(),r.walkingSpeedMps())||e.getConfirmationTimeoutSec()!=r.confirmationTimeoutSec()||!Objects.equals(e.getEscortNote(),r.escortNote()))replayConflict();}
    private ObjectNode replayData(ServiceRequest existing){ObjectNode value=data(existing.getRequestDataJson());value.remove(List.of("device_cancel_idempotency_key","device_cancel_reason"));return value;}
    private boolean sameDecimal(java.math.BigDecimal left,java.math.BigDecimal right){return left==null?right==null:right!=null&&left.compareTo(right)==0;}
    private void replayConflict(){throw new ConflictException("Idempotency-Key was reused with a different payload");}
    private NavigationResponse navigation(ServiceRequest r){return navigation(navigationRepository.findById(r.getServiceRequestId()).orElseThrow(()->new ConflictException("Navigation subtype is missing")));}
    private NavigationResponse navigation(NavigationRequest n){return new NavigationResponse(mapper.toResponse(n.getServiceRequest()),n.getMap().getMapId(),id(n.getWorkflow()),id(n.getRoom()),id(n.getOriginEndpoint()),id(n.getDestinationEndpoint()),gid(n.getDestinationGroup()),id(n.getResolvedEndpoint()),n.getTargetMode(),n.getNavigationMode(),n.getAccessibilityNeed(),n.getPassengerCount(),n.getArrivalInstruction());}
    private EscortResponse escort(ServiceRequest r){return escort(escortRepository.findById(r.getServiceRequestId()).orElseThrow(()->new ConflictException("Escort subtype is missing")));}
    private EscortResponse escort(EscortRequest e){return new EscortResponse(mapper.toResponse(e.getServiceRequest()),e.getMap().getMapId(),id(e.getOriginRoom()),id(e.getDestinationRoom()),id(e.getOriginEndpoint()),id(e.getDestinationEndpoint()),gid(e.getDestinationGroup()),id(e.getResolvedEndpoint()),e.getTargetMode(),e.getPatientReference(),e.getEscortMode(),e.getWalkingSpeedMps(),e.getConfirmationTimeoutSec(),e.getEscortNote());}
    private UUID id(Room x){return x==null?null:x.getRoomId();} private UUID id(Endpoint x){return x==null?null:x.getEndpointId();} private UUID id(Workflow x){return x==null?null:x.getWorkflowId();} private UUID gid(EndpointGroup x){return x==null?null:x.getEndpointGroupId();}
    private void validateWindow(Instant from,Instant to){if(from!=null&&to!=null&&!from.isBefore(to))throw new BusinessRuleException("from must be before to");}
    private <T> Page<T> page(List<T> values,Pageable p){int start=(int)Math.min(p.getOffset(),values.size());int end=Math.min(start+p.getPageSize(),values.size());return new PageImpl<>(values.subList(start,end),p,values.size());}
    private RequestSourceType source(Role role){return RequestSourceType.STAFF_UI;}
    private ServiceRequest request(UUID id){return requestRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("ServiceRequest not found: "+id));}
    private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Account not found: "+id));}
    private FacilityMap map(UUID id){return mapRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Map not found: "+id));}
    private Room room(UUID id){return id==null?null:roomRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Room not found: "+id));}
    private Endpoint endpoint(UUID id){return id==null?null:endpointRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Endpoint not found: "+id));}
    private EndpointGroup group(UUID id){return id==null?null:groupRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("EndpointGroup not found: "+id));}
    private Workflow navigationWorkflow(UUID id){if(id==null)return null;Workflow workflow=workflowRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Workflow not found: "+id));if(workflow.getStatus()!=WorkflowStatus.PUBLISHED||!workflow.isAcceptsNewJobs()||!"NAVIGATION".equalsIgnoreCase(workflow.getServiceType()))throw new BusinessRuleException("Selected workflow must be a published NAVIGATION workflow that accepts new Jobs");return workflow;}
    private void audit(RequestActor actor,String action,ServiceRequest request,RequestMetadata md){Map<String,Object> after=new LinkedHashMap<>();after.put("status",request.getStatus().name());if(actor.robotId()!=null)after.put("robotId",actor.robotId());auditService.record(actor.accountId()==null?null:account(actor.accountId()),action,"ServiceRequest",request.getServiceRequestId(),null,after,md);}

    @Override @Transactional public ServiceRequestResponse createDeviceInformation(InformationCreateRequest r,String key,com.mediassistrover.backend.security.DevicePrincipal actor,RequestMetadata md){
        Robot device=deviceScope.requireCurrentDevice(actor);ServiceRequestResponse response=createInformation(r,key,new RequestActor(null,device.getRobotId()),md);
        submitDevice(response.serviceRequestId());return mapper.toResponse(request(response.serviceRequestId()));
    }
    @Override @Transactional public NavigationResponse createDeviceNavigation(NavigationCreateRequest r,String key,com.mediassistrover.backend.security.DevicePrincipal actor,RequestMetadata md){
        Robot device=deviceScope.requireCurrentDevice(actor);RequestActor owner=new RequestActor(null,device.getRobotId());
        createNavigation(r,key,owner,md);ServiceRequest existing=existing(key,owner);submitDevice(existing.getServiceRequestId());return navigation(existing);
    }
    @Override @Transactional public EscortResponse createDeviceEscort(EscortCreateRequest r,String key,com.mediassistrover.backend.security.DevicePrincipal actor,RequestMetadata md){
        Robot device=deviceScope.requireCurrentDevice(actor);RequestActor owner=new RequestActor(null,device.getRobotId());
        createEscort(r,key,owner,md);ServiceRequest existing=existing(key,owner);submitDevice(existing.getServiceRequestId());return escort(existing);
    }
    @Override public DeviceRequestDetailResponse getDeviceRequest(UUID id,com.mediassistrover.backend.security.DevicePrincipal actor){ServiceRequest value=request(id);deviceScope.requireRequestOwner(value,actor);return deviceDetail(value);}
    @Override @Transactional public DeviceRequestDetailResponse cancelDeviceRequest(UUID id,String reason,String key,com.mediassistrover.backend.security.DevicePrincipal actor,RequestMetadata md){
        if(reason==null||reason.isBlank()||reason.length()>1000)throw new BusinessRuleException("Cancellation reason is required and must be at most 1000 characters");
        if(key!=null&&(key.isBlank()||key.length()>150))throw new BusinessRuleException("Idempotency-Key must be 1-150 characters when provided");
        ServiceRequest value=request(id);deviceScope.requireRequestOwner(value,actor);ObjectNode data=data(value.getRequestDataJson());
        if(key!=null&&data.hasNonNull("device_cancel_idempotency_key")){
            if(!key.equals(data.path("device_cancel_idempotency_key").asText())||!reason.equals(data.path("device_cancel_reason").asText()))throw new ConflictException("Idempotency-Key was reused with a different cancellation payload");
            return deviceDetail(value);
        }
        if(isTerminal(value.getStatus()))return deviceDetail(value);
        if(key!=null){data.put("device_cancel_idempotency_key",key);data.put("device_cancel_reason",reason);value.setRequestDataJson(data);}
        Instant now=Instant.now(clock);boolean uncertain=false;
        for(Job candidate:jobRepository.findByServiceRequestServiceRequestId(id)){Job job=jobRepository.lockById(candidate.getJobId()).orElseThrow();
            if(job.getStatus()==JobStatus.RUNNING){uncertain=true;job.setStatus(JobStatus.RECOVERY_REQUIRED);job.setErrorCode("DEVICE_CANCEL_PHYSICAL_STATE_UNKNOWN");job.setFailureReason(reason);if(job.getRobot()!=null)job.getRobot().setStatus(RobotStatus.ERROR);}
            else if(!Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED,JobStatus.RECOVERY_REQUIRED).contains(job.getStatus())&&job.getCancelCommandId()==null){job.setCancelCommandId(UUID.randomUUID());job.setCancelRequestedAt(now);}
            wakeups.request(job);
        }
        value.setStatus(uncertain?ServiceRequestStatus.RECOVERY_REQUIRED:ServiceRequestStatus.CANCELLED);if(!uncertain)value.setCancelledAt(now);
        audit(new RequestActor(null,actor.robotId()),uncertain?"DEVICE_REQUEST_RECOVERY_REQUIRED":"DEVICE_REQUEST_CANCELLED",value,md);return deviceDetail(value);
    }
    private void submitDevice(UUID id){
        ServiceRequest request=request(id);
        if(request.getStatus()!=ServiceRequestStatus.DRAFT)return;
        validateSubtype(request);request.setStatus(ServiceRequestStatus.SUBMITTED);
        jobExecutionService.createForRequest(request);request.setStatus(ServiceRequestStatus.QUEUED);
    }
    private Robot robot(UUID id){return robotRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Robot not found"));}
    private boolean isTerminal(ServiceRequestStatus status){return Set.of(ServiceRequestStatus.COMPLETED,ServiceRequestStatus.FAILED,ServiceRequestStatus.CANCELLED,ServiceRequestStatus.RECOVERY_REQUIRED).contains(status);}
    private DeviceRequestDetailResponse deviceDetail(ServiceRequest value){List<DeviceJobSummary> jobs=jobRepository.findByServiceRequestServiceRequestId(value.getServiceRequestId()).stream().sorted(Comparator.comparing(Job::getQueuedAt).thenComparing(Job::getJobId)).map(j->new DeviceJobSummary(j.getJobId(),j.getJobCode(),j.getStatus(),j.getRobot()==null?null:j.getRobot().getRobotId(),j.getErrorCode(),j.getFailureReason(),j.getQueuedAt(),j.getStartedAt(),j.getEndedAt())).toList();return new DeviceRequestDetailResponse(value.getServiceRequestId(),value.getRequestCode(),value.getRequestType(),value.getStatus(),value.getPriority(),value.getRequestNote(),value.getRequestedAt(),value.getCancelledAt(),value.getCompletedAt(),jobs);}
    private record RequestActor(UUID accountId,UUID robotId){}
}
