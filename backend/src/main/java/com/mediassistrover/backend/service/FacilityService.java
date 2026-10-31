package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.facility.request.FacilityRequests.*;
import com.mediassistrover.backend.dto.facility.response.FacilityResponses.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface FacilityService {
    HospitalInformationResponse createHospitalInformation(HospitalInformationRequest request, UUID actorId, RequestMetadata metadata);
    HospitalInformationResponse updateHospitalInformation(UUID id, HospitalInformationRequest request, UUID actorId, RequestMetadata metadata);
    HospitalInformationResponse publishHospitalInformation(UUID id, UUID actorId, RequestMetadata metadata);
    HospitalInformationResponse retireHospitalInformation(UUID id, UUID actorId, RequestMetadata metadata);
    Page<HospitalInformationResponse> listHospitalInformation(Pageable pageable);
    HospitalInformationResponse getHospitalInformation(UUID id);

    FloorResponse createFloor(FloorRequest request, UUID actorId, RequestMetadata metadata);
    FloorResponse updateFloor(UUID id, FloorRequest request, UUID actorId, RequestMetadata metadata);
    FloorResponse setFloorActive(UUID id, boolean active, UUID actorId, RequestMetadata metadata);
    Page<FloorResponse> listFloors(Pageable pageable);
    FloorResponse getFloor(UUID id);

    MapResponse createMap(MapRequest request, UUID actorId, RequestMetadata metadata);
    MapResponse updateMap(UUID id, MapRequest request, UUID actorId, RequestMetadata metadata);
    MapResponse activateMap(UUID id, UUID actorId, RequestMetadata metadata);
    MapResponse archiveMap(UUID id, UUID actorId, RequestMetadata metadata);
    MapArtifactResponse getMapArtifact(UUID id);
    Page<MapResponse> listMaps(Pageable pageable);
    MapResponse getMap(UUID id);

    ZoneResponse createZone(ZoneRequest request, UUID actorId, RequestMetadata metadata);
    ZoneResponse updateZone(UUID id, ZoneRequest request, UUID actorId, RequestMetadata metadata);
    ZoneResponse setZoneActive(UUID id, boolean active, UUID actorId, RequestMetadata metadata);
    Page<ZoneResponse> listZones(Pageable pageable);
    ZoneResponse getZone(UUID id);

    RoomResponse createRoom(RoomRequest request, UUID actorId, RequestMetadata metadata);
    RoomResponse updateRoom(UUID id, RoomRequest request, UUID actorId, RequestMetadata metadata);
    RoomResponse setRoomActive(UUID id, boolean active, UUID actorId, RequestMetadata metadata);
    Page<RoomResponse> listRooms(Pageable pageable);
    RoomResponse getRoom(UUID id);

    EndpointResponse createEndpoint(EndpointRequest request, UUID actorId, RequestMetadata metadata);
    EndpointResponse updateEndpoint(UUID id, EndpointRequest request, UUID actorId, RequestMetadata metadata);
    EndpointResponse setEndpointActive(UUID id, boolean active, UUID actorId, RequestMetadata metadata);
    Page<EndpointResponse> listEndpoints(Pageable pageable);
    EndpointResponse getEndpoint(UUID id);

    EndpointGroupResponse createEndpointGroup(EndpointGroupRequest request, UUID actorId, RequestMetadata metadata);
    EndpointGroupResponse updateEndpointGroup(UUID id, EndpointGroupRequest request, UUID actorId, RequestMetadata metadata);
    EndpointGroupResponse setEndpointGroupActive(UUID id, boolean active, UUID actorId, RequestMetadata metadata);
    Page<EndpointGroupResponse> listEndpointGroups(Pageable pageable);
    EndpointGroupResponse getEndpointGroup(UUID id);

    EndpointGroupMemberResponse createEndpointGroupMember(EndpointGroupMemberRequest request, UUID actorId, RequestMetadata metadata);
    EndpointGroupMemberResponse updateEndpointGroupMember(UUID groupId, UUID endpointId, EndpointGroupMemberRequest request, UUID actorId, RequestMetadata metadata);
    void removeEndpointGroupMember(UUID groupId, UUID endpointId, UUID actorId, RequestMetadata metadata);
    Page<EndpointGroupMemberResponse> listEndpointGroupMembers(Pageable pageable);

    Page<HospitalInformationResponse> listPublicHospitalInformation(Pageable pageable);
    Page<FloorResponse> listPublicFloors(Pageable pageable);
    Page<ZoneResponse> listPublicZones(Pageable pageable);
    Page<RoomResponse> listPublicRooms(Pageable pageable);
    Page<EndpointResponse> listPublicEndpoints(Pageable pageable);
}
