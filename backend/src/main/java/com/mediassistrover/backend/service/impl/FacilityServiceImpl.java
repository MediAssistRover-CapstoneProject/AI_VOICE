package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.facility.request.FacilityRequests.*;
import com.mediassistrover.backend.dto.facility.response.FacilityResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.enums.MapStatus;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ConflictException;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.mapper.FacilityMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.AuditService;
import com.mediassistrover.backend.service.FacilityService;
import com.mediassistrover.backend.service.RequestMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityServiceImpl implements FacilityService {
    private final HospitalInformationRepository hospitalRepository;
    private final FloorRepository floorRepository;
    private final FacilityMapRepository mapRepository;
    private final ZoneRepository zoneRepository;
    private final RoomRepository roomRepository;
    private final EndpointRepository endpointRepository;
    private final EndpointGroupRepository groupRepository;
    private final EndpointGroupMemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final FacilityMapper mapper;
    private final AuditService auditService;
    private final Clock clock;

    @Override @Transactional
    public HospitalInformationResponse createHospitalInformation(HospitalInformationRequest r, UUID actorId, RequestMetadata md) {
        validateEffectiveWindow(r.effectiveFrom(), r.effectiveUntil());
        Account actor = account(actorId);
        HospitalInformation entity = mapper.toEntity(r);
        entity.setUpdatedBy(actor);
        entity = hospitalRepository.save(entity);
        audit(actor, "HOSPITAL_INFORMATION_CREATED", "HospitalInformation", entity.getHospitalInformationId(), md);
        return mapper.toResponse(entity);
    }

    @Override @Transactional
    public HospitalInformationResponse updateHospitalInformation(UUID id, HospitalInformationRequest r, UUID actorId, RequestMetadata md) {
        validateEffectiveWindow(r.effectiveFrom(), r.effectiveUntil());
        HospitalInformation entity = hospital(id);
        if (entity.getStatus() == HospitalInformationStatus.RETIRED) throw new BusinessRuleException("Retired hospital information cannot be updated");
        Account actor = account(actorId);
        mapper.update(r, entity); entity.setUpdatedBy(actor);
        audit(actor, "HOSPITAL_INFORMATION_UPDATED", "HospitalInformation", id, md);
        return mapper.toResponse(entity);
    }

    @Override @Transactional
    public HospitalInformationResponse publishHospitalInformation(UUID id, UUID actorId, RequestMetadata md) {
        HospitalInformation entity = hospital(id);
        if (entity.getStatus() != HospitalInformationStatus.DRAFT) throw new BusinessRuleException("Only DRAFT hospital information can be published");
        entity.setStatus(HospitalInformationStatus.PUBLISHED);
        Account actor = account(actorId); entity.setUpdatedBy(actor);
        audit(actor, "HOSPITAL_INFORMATION_PUBLISHED", "HospitalInformation", id, md);
        return mapper.toResponse(entity);
    }

    @Override @Transactional
    public HospitalInformationResponse retireHospitalInformation(UUID id, UUID actorId, RequestMetadata md) {
        HospitalInformation entity = hospital(id); entity.setStatus(HospitalInformationStatus.RETIRED);
        Account actor = account(actorId); entity.setUpdatedBy(actor);
        audit(actor, "HOSPITAL_INFORMATION_RETIRED", "HospitalInformation", id, md);
        return mapper.toResponse(entity);
    }
    @Override public Page<HospitalInformationResponse> listHospitalInformation(Pageable p) { return hospitalRepository.findAll(p).map(mapper::toResponse); }
    @Override public HospitalInformationResponse getHospitalInformation(UUID id){return mapper.toResponse(hospital(id));}

    @Override @Transactional
    public FloorResponse createFloor(FloorRequest r, UUID actorId, RequestMetadata md) {
        HospitalInformation hospital = hospitalProfile(r.hospitalInformationId());
        if (floorRepository.existsByHospitalInformationHospitalInformationIdAndFloorCodeIgnoreCase(r.hospitalInformationId(), r.floorCode())) throw new ConflictException("Floor code already exists in this hospital");
        Floor entity = mapper.toEntity(r); entity.setHospitalInformation(hospital); entity = floorRepository.save(entity);
        audit(account(actorId), "FLOOR_CREATED", "Floor", entity.getFloorId(), md); return mapper.toResponse(entity);
    }
    @Override @Transactional
    public FloorResponse updateFloor(UUID id, FloorRequest r, UUID actorId, RequestMetadata md) {
        Floor entity = floor(id); HospitalInformation hospital = hospitalProfile(r.hospitalInformationId());
        if ((!entity.getHospitalInformation().getHospitalInformationId().equals(r.hospitalInformationId()) || !entity.getFloorCode().equalsIgnoreCase(r.floorCode()))
                && floorRepository.existsByHospitalInformationHospitalInformationIdAndFloorCodeIgnoreCase(r.hospitalInformationId(), r.floorCode())) throw new ConflictException("Floor code already exists in this hospital");
        mapper.update(r, entity); entity.setHospitalInformation(hospital);
        audit(account(actorId), "FLOOR_UPDATED", "Floor", id, md); return mapper.toResponse(entity);
    }
    @Override @Transactional public FloorResponse setFloorActive(UUID id, boolean active, UUID actorId, RequestMetadata md) {
        Floor e=floor(id);e.setActive(active);audit(account(actorId), active?"FLOOR_ACTIVATED":"FLOOR_DEACTIVATED","Floor",id,md);return mapper.toResponse(e);
    }
    @Override public Page<FloorResponse> listFloors(Pageable p) { return floorRepository.findAll(p).map(mapper::toResponse); }
    @Override public FloorResponse getFloor(UUID id){return mapper.toResponse(floor(id));}

    @Override @Transactional
    public MapResponse createMap(MapRequest r, UUID actorId, RequestMetadata md) {
        if (mapRepository.existsByMapCodeIgnoreCaseAndVersionNo(r.mapCode(), r.versionNo())) throw new ConflictException("Map code and version already exist");
        FacilityMap entity=mapper.toEntity(r);entity.setFloor(floor(r.floorId()));entity=mapRepository.save(entity);
        audit(account(actorId),"MAP_CREATED","Map",entity.getMapId(),md);return mapper.toResponse(entity);
    }
    @Override @Transactional
    public MapResponse updateMap(UUID id, MapRequest r, UUID actorId, RequestMetadata md) {
        FacilityMap entity=facilityMap(id);
        if(entity.getStatus()!=MapStatus.DRAFT) throw new BusinessRuleException("Only DRAFT map metadata can be updated");
        if((!entity.getMapCode().equalsIgnoreCase(r.mapCode())||entity.getVersionNo()!=r.versionNo())&&mapRepository.existsByMapCodeIgnoreCaseAndVersionNo(r.mapCode(),r.versionNo())) throw new ConflictException("Map code and version already exist");
        mapper.update(r,entity);entity.setFloor(floor(r.floorId()));audit(account(actorId),"MAP_UPDATED","Map",id,md);return mapper.toResponse(entity);
    }
    @Override @Transactional
    public MapResponse activateMap(UUID id, UUID actorId, RequestMetadata md) {
        FacilityMap target=facilityMap(id);
        if(target.getStatus()==MapStatus.ARCHIVED) throw new BusinessRuleException("Archived map cannot be activated");
        floorRepository.findByIdForUpdate(target.getFloor().getFloorId()).orElseThrow(() -> notFound("Floor", target.getFloor().getFloorId()));
        mapRepository.findAllByFloorAndStatusForUpdate(target.getFloor().getFloorId(),MapStatus.ACTIVE).stream()
                .filter(current -> !current.getMapId().equals(id)).forEach(current -> current.setStatus(MapStatus.INACTIVE));
        target.setStatus(MapStatus.ACTIVE);target.setCommissionedAt(Instant.now(clock));
        audit(account(actorId),"MAP_ACTIVATED","Map",id,md);return mapper.toResponse(target);
    }
    @Override @Transactional public MapResponse archiveMap(UUID id, UUID actorId, RequestMetadata md) {
        FacilityMap e=facilityMap(id);e.setStatus(MapStatus.ARCHIVED);audit(account(actorId),"MAP_ARCHIVED","Map",id,md);return mapper.toResponse(e);
    }
    @Override public MapArtifactResponse getMapArtifact(UUID id) { FacilityMap e=facilityMap(id);return new MapArtifactResponse(e.getMapId(),e.getMapCode(),e.getVersionNo(),e.getMapFileUri(),e.getAssetHash(),e.getFrameId(),e.getStatus()); }
    @Override public Page<MapResponse> listMaps(Pageable p) { return mapRepository.findAll(p).map(mapper::toResponse); }
    @Override public MapResponse getMap(UUID id){return mapper.toResponse(facilityMap(id));}

    @Override @Transactional public ZoneResponse createZone(ZoneRequest r,UUID a,RequestMetadata md){
        if(zoneRepository.existsByMapMapIdAndZoneCodeIgnoreCase(r.mapId(),r.zoneCode()))throw new ConflictException("Zone code already exists in this map");
        Zone e=mapper.toEntity(r);e.setMap(facilityMap(r.mapId()));e=zoneRepository.save(e);audit(account(a),"ZONE_CREATED","Zone",e.getZoneId(),md);return mapper.toResponse(e);}
    @Override @Transactional public ZoneResponse updateZone(UUID id,ZoneRequest r,UUID a,RequestMetadata md){Zone e=zone(id);
        if((!e.getMap().getMapId().equals(r.mapId())||!e.getZoneCode().equalsIgnoreCase(r.zoneCode()))&&zoneRepository.existsByMapMapIdAndZoneCodeIgnoreCase(r.mapId(),r.zoneCode()))throw new ConflictException("Zone code already exists in this map");
        mapper.update(r,e);e.setMap(facilityMap(r.mapId()));audit(account(a),"ZONE_UPDATED","Zone",id,md);return mapper.toResponse(e);}
    @Override @Transactional public ZoneResponse setZoneActive(UUID id,boolean active,UUID a,RequestMetadata md){Zone e=zone(id);e.setActive(active);audit(account(a),active?"ZONE_ACTIVATED":"ZONE_DEACTIVATED","Zone",id,md);return mapper.toResponse(e);}
    @Override public Page<ZoneResponse> listZones(Pageable p){return zoneRepository.findAll(p).map(mapper::toResponse);}
    @Override public ZoneResponse getZone(UUID id){return mapper.toResponse(zone(id));}

    @Override @Transactional public RoomResponse createRoom(RoomRequest r,UUID a,RequestMetadata md){
        if(roomRepository.existsByRoomCodeIgnoreCase(r.roomCode()))throw new ConflictException("Room code already exists");
        Room e=mapper.toEntity(r);bindRoom(e,r);e=roomRepository.save(e);audit(account(a),"ROOM_CREATED","Room",e.getRoomId(),md);return mapper.toResponse(e);}
    @Override @Transactional public RoomResponse updateRoom(UUID id,RoomRequest r,UUID a,RequestMetadata md){Room e=room(id);
        if(!e.getRoomCode().equalsIgnoreCase(r.roomCode())&&roomRepository.existsByRoomCodeIgnoreCase(r.roomCode()))throw new ConflictException("Room code already exists");
        mapper.update(r,e);bindRoom(e,r);audit(account(a),"ROOM_UPDATED","Room",id,md);return mapper.toResponse(e);}
    @Override @Transactional public RoomResponse setRoomActive(UUID id,boolean active,UUID a,RequestMetadata md){Room e=room(id);e.setActive(active);audit(account(a),active?"ROOM_ACTIVATED":"ROOM_DEACTIVATED","Room",id,md);return mapper.toResponse(e);}
    @Override public Page<RoomResponse> listRooms(Pageable p){return roomRepository.findAll(p).map(mapper::toResponse);}
    @Override public RoomResponse getRoom(UUID id){return mapper.toResponse(room(id));}

    @Override @Transactional public EndpointResponse createEndpoint(EndpointRequest r,UUID a,RequestMetadata md){
        if(endpointRepository.existsByMapMapIdAndEndpointCodeIgnoreCase(r.mapId(),r.endpointCode()))throw new ConflictException("Endpoint code already exists in this map");
        Endpoint e=mapper.toEntity(r);bindEndpoint(e,r);e=endpointRepository.save(e);audit(account(a),"ENDPOINT_CREATED","Endpoint",e.getEndpointId(),md);return mapper.toResponse(e);}
    @Override @Transactional public EndpointResponse updateEndpoint(UUID id,EndpointRequest r,UUID a,RequestMetadata md){Endpoint e=endpoint(id);
        if((!e.getMap().getMapId().equals(r.mapId())||!e.getEndpointCode().equalsIgnoreCase(r.endpointCode()))&&endpointRepository.existsByMapMapIdAndEndpointCodeIgnoreCase(r.mapId(),r.endpointCode()))throw new ConflictException("Endpoint code already exists in this map");
        mapper.update(r,e);bindEndpoint(e,r);audit(account(a),"ENDPOINT_UPDATED","Endpoint",id,md);return mapper.toResponse(e);}
    @Override @Transactional public EndpointResponse setEndpointActive(UUID id,boolean active,UUID a,RequestMetadata md){Endpoint e=endpoint(id);e.setActive(active);audit(account(a),active?"ENDPOINT_ACTIVATED":"ENDPOINT_DEACTIVATED","Endpoint",id,md);return mapper.toResponse(e);}
    @Override public Page<EndpointResponse> listEndpoints(Pageable p){return endpointRepository.findAll(p).map(mapper::toResponse);}
    @Override public EndpointResponse getEndpoint(UUID id){return mapper.toResponse(endpoint(id));}

    @Override @Transactional public EndpointGroupResponse createEndpointGroup(EndpointGroupRequest r,UUID a,RequestMetadata md){
        if(groupRepository.existsByMapMapIdAndGroupCodeIgnoreCase(r.mapId(),r.groupCode()))throw new ConflictException("Endpoint group code already exists in this map");
        EndpointGroup e=mapper.toEntity(r);e.setMap(facilityMap(r.mapId()));e=groupRepository.save(e);audit(account(a),"ENDPOINT_GROUP_CREATED","EndpointGroup",e.getEndpointGroupId(),md);return mapper.toResponse(e);}
    @Override @Transactional public EndpointGroupResponse updateEndpointGroup(UUID id,EndpointGroupRequest r,UUID a,RequestMetadata md){EndpointGroup e=group(id);
        if((!e.getMap().getMapId().equals(r.mapId())||!e.getGroupCode().equalsIgnoreCase(r.groupCode()))&&groupRepository.existsByMapMapIdAndGroupCodeIgnoreCase(r.mapId(),r.groupCode()))throw new ConflictException("Endpoint group code already exists in this map");
        mapper.update(r,e);e.setMap(facilityMap(r.mapId()));audit(account(a),"ENDPOINT_GROUP_UPDATED","EndpointGroup",id,md);return mapper.toResponse(e);}
    @Override @Transactional public EndpointGroupResponse setEndpointGroupActive(UUID id,boolean active,UUID a,RequestMetadata md){EndpointGroup e=group(id);e.setActive(active);audit(account(a),active?"ENDPOINT_GROUP_ACTIVATED":"ENDPOINT_GROUP_DEACTIVATED","EndpointGroup",id,md);return mapper.toResponse(e);}
    @Override public Page<EndpointGroupResponse> listEndpointGroups(Pageable p){return groupRepository.findAll(p).map(mapper::toResponse);}
    @Override public EndpointGroupResponse getEndpointGroup(UUID id){return mapper.toResponse(group(id));}

    @Override @Transactional public EndpointGroupMemberResponse createEndpointGroupMember(EndpointGroupMemberRequest r,UUID a,RequestMetadata md){
        EndpointGroupMemberId id=new EndpointGroupMemberId(r.endpointGroupId(),r.endpointId());if(memberRepository.existsById(id))throw new ConflictException("Endpoint is already in this group");
        if(memberRepository.existsByEndpointGroupEndpointGroupIdAndSequenceNo(r.endpointGroupId(),r.sequenceNo()))throw new ConflictException("Sequence number already exists in this group");
        EndpointGroup g=group(r.endpointGroupId());Endpoint e=endpoint(r.endpointId());ensureSameMap(g,e);
        EndpointGroupMember m=EndpointGroupMember.builder().id(id).endpointGroup(g).endpoint(e).map(g.getMap()).sequenceNo(r.sequenceNo()).active(r.active()).build();m=memberRepository.save(m);
        audit(account(a),"ENDPOINT_GROUP_MEMBER_CREATED","EndpointGroup",g.getEndpointGroupId(),md);return mapper.toResponse(m);}
    @Override @Transactional public EndpointGroupMemberResponse updateEndpointGroupMember(UUID groupId,UUID endpointId,EndpointGroupMemberRequest r,UUID a,RequestMetadata md){
        if(!groupId.equals(r.endpointGroupId())||!endpointId.equals(r.endpointId()))throw new BusinessRuleException("Path IDs must match request IDs");
        EndpointGroupMember m=memberRepository.findById(new EndpointGroupMemberId(groupId,endpointId)).orElseThrow(()->new ResourceNotFoundException("Endpoint group member not found"));
        if(m.getSequenceNo()!=r.sequenceNo()&&memberRepository.existsByEndpointGroupEndpointGroupIdAndSequenceNo(groupId,r.sequenceNo()))throw new ConflictException("Sequence number already exists in this group");
        m.setSequenceNo(r.sequenceNo());m.setActive(r.active());audit(account(a),"ENDPOINT_GROUP_MEMBER_UPDATED","EndpointGroup",groupId,md);return mapper.toResponse(m);}
    @Override @Transactional public void removeEndpointGroupMember(UUID groupId,UUID endpointId,UUID a,RequestMetadata md){EndpointGroupMember m=memberRepository.findById(new EndpointGroupMemberId(groupId,endpointId)).orElseThrow(()->new ResourceNotFoundException("Endpoint group member not found"));m.setActive(false);audit(account(a),"ENDPOINT_GROUP_MEMBER_DEACTIVATED","EndpointGroup",groupId,md);}
    @Override public Page<EndpointGroupMemberResponse> listEndpointGroupMembers(Pageable p){return memberRepository.findAll(p).map(mapper::toResponse);}

    @Override public Page<HospitalInformationResponse> listPublicHospitalInformation(Pageable p){return hospitalRepository.findPublicEffective(HospitalInformationStatus.PUBLISHED,Instant.now(clock),p).map(mapper::toResponse);}
    @Override public Page<FloorResponse> listPublicFloors(Pageable p){return floorRepository.findByActiveTrue(p).map(mapper::toResponse);}
    @Override public Page<ZoneResponse> listPublicZones(Pageable p){return zoneRepository.findByActiveTrueAndPublicVisibleTrue(p).map(mapper::toResponse);}
    @Override public Page<RoomResponse> listPublicRooms(Pageable p){return roomRepository.findByActiveTrueAndPublicVisibleTrue(p).map(mapper::toResponse);}
    @Override public Page<EndpointResponse> listPublicEndpoints(Pageable p){return endpointRepository.findByActiveTrueAndPublicVisibleTrue(p).map(mapper::toResponse);}

    private void bindRoom(Room e,RoomRequest r){Floor f=floor(r.floorId());e.setFloor(f);if(r.defaultEndpointGroupId()==null){e.setDefaultEndpointGroup(null);return;}EndpointGroup g=group(r.defaultEndpointGroupId());if(!g.getMap().getFloor().getFloorId().equals(f.getFloorId()))throw new BusinessRuleException("Default endpoint group must belong to the room floor");e.setDefaultEndpointGroup(g);}
    private void bindEndpoint(Endpoint e,EndpointRequest r){FacilityMap map=facilityMap(r.mapId());e.setMap(map);Zone z=r.zoneId()==null?null:zone(r.zoneId());Room room=r.roomId()==null?null:room(r.roomId());if(z!=null&&!z.getMap().getMapId().equals(map.getMapId()))throw new BusinessRuleException("Zone and endpoint must use the same map");if(room!=null&&!room.getFloor().getFloorId().equals(map.getFloor().getFloorId()))throw new BusinessRuleException("Room and endpoint map must belong to the same floor");e.setZone(z);e.setRoom(room);}
    private void ensureSameMap(EndpointGroup g,Endpoint e){if(!g.getMap().getMapId().equals(e.getMap().getMapId()))throw new BusinessRuleException("Endpoint group and endpoint must use the same map");}
    private void validateEffectiveWindow(Instant from,Instant until){if(from!=null&&until!=null&&!until.isAfter(from))throw new BusinessRuleException("effectiveUntil must be after effectiveFrom");}
    private HospitalInformation hospitalProfile(UUID id){HospitalInformation h=hospital(id);if(!"HOSPITAL_PROFILE".equalsIgnoreCase(h.getInfoCategory()))throw new BusinessRuleException("Floor must reference HospitalInformation category HOSPITAL_PROFILE");return h;}
    private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->notFound("Account",id));}
    private HospitalInformation hospital(UUID id){return hospitalRepository.findById(id).orElseThrow(()->notFound("HospitalInformation",id));}
    private Floor floor(UUID id){return floorRepository.findById(id).orElseThrow(()->notFound("Floor",id));}
    private FacilityMap facilityMap(UUID id){return mapRepository.findById(id).orElseThrow(()->notFound("Map",id));}
    private Zone zone(UUID id){return zoneRepository.findById(id).orElseThrow(()->notFound("Zone",id));}
    private Room room(UUID id){return roomRepository.findById(id).orElseThrow(()->notFound("Room",id));}
    private Endpoint endpoint(UUID id){return endpointRepository.findById(id).orElseThrow(()->notFound("Endpoint",id));}
    private EndpointGroup group(UUID id){return groupRepository.findById(id).orElseThrow(()->notFound("EndpointGroup",id));}
    private ResourceNotFoundException notFound(String type,UUID id){return new ResourceNotFoundException(type+" not found: "+id);}
    private void audit(Account actor,String action,String type,UUID id,RequestMetadata md){auditService.record(actor,action,type,id,null,Map.of("action",action),md);}
}
