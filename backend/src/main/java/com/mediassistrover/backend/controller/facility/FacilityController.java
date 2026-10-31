package com.mediassistrover.backend.controller.facility;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.facility.request.FacilityRequests.*;
import com.mediassistrover.backend.dto.facility.response.FacilityResponses.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.FacilityService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Tag(name = "Facility Catalog", description = "ADMIN quản lý; ADMIN và STAFF được đọc")
public class FacilityController {
    private final FacilityService service;

    @GetMapping("/hospital-information") public PageResponse<HospitalInformationResponse> hospitals(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listHospitalInformation(page(page,size,"createdAt")));}
    @GetMapping("/hospital-information/{id}") public HospitalInformationResponse hospital(@PathVariable UUID id){return service.getHospitalInformation(id);}
    @PostMapping("/hospital-information") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public HospitalInformationResponse createHospital(@Valid @RequestBody HospitalInformationRequest body,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createHospitalInformation(body,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/hospital-information/{id}") @PreAuthorize("hasRole('ADMIN')") public HospitalInformationResponse updateHospital(@PathVariable UUID id,@Valid @RequestBody HospitalInformationRequest body,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateHospitalInformation(id,body,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/hospital-information/{id}/publish") @PreAuthorize("hasRole('ADMIN')") public HospitalInformationResponse publishHospital(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.publishHospitalInformation(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/hospital-information/{id}/retire") @PreAuthorize("hasRole('ADMIN')") public HospitalInformationResponse retireHospital(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.retireHospitalInformation(id,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/floors") public PageResponse<FloorResponse> floors(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listFloors(page(page,size,"displayOrder")));}
    @GetMapping("/floors/{id}") public FloorResponse floor(@PathVariable UUID id){return service.getFloor(id);}
    @PostMapping("/floors") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public FloorResponse createFloor(@Valid @RequestBody FloorRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createFloor(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/floors/{id}") @PreAuthorize("hasRole('ADMIN')") public FloorResponse updateFloor(@PathVariable UUID id,@Valid @RequestBody FloorRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateFloor(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/floors/{id}/activate") @PreAuthorize("hasRole('ADMIN')") public FloorResponse activateFloor(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setFloorActive(id,true,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/floors/{id}/deactivate") @PreAuthorize("hasRole('ADMIN')") public FloorResponse deactivateFloor(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setFloorActive(id,false,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/maps") public PageResponse<MapResponse> maps(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listMaps(page(page,size,"createdAt")));}
    @GetMapping("/maps/{id}") public MapResponse map(@PathVariable UUID id){return service.getMap(id);}
    @GetMapping("/maps/{id}/artifact-metadata") public MapArtifactResponse artifact(@PathVariable UUID id){return service.getMapArtifact(id);}
    @PostMapping("/maps") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public MapResponse createMap(@Valid @RequestBody MapRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createMap(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/maps/{id}") @PreAuthorize("hasRole('ADMIN')") public MapResponse updateMap(@PathVariable UUID id,@Valid @RequestBody MapRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateMap(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/maps/{id}/activate") @PreAuthorize("hasRole('ADMIN')") @Operation(summary="Kích hoạt Map và vô hiệu Map active cũ trên cùng Floor") public MapResponse activateMap(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.activateMap(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/maps/{id}/archive") @PreAuthorize("hasRole('ADMIN')") public MapResponse archiveMap(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.archiveMap(id,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/zones") public PageResponse<ZoneResponse> zones(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listZones(page(page,size,"createdAt")));}
    @GetMapping("/zones/{id}") public ZoneResponse zone(@PathVariable UUID id){return service.getZone(id);}
    @PostMapping("/zones") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public ZoneResponse createZone(@Valid @RequestBody ZoneRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createZone(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/zones/{id}") @PreAuthorize("hasRole('ADMIN')") public ZoneResponse updateZone(@PathVariable UUID id,@Valid @RequestBody ZoneRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateZone(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/zones/{id}/activate") @PreAuthorize("hasRole('ADMIN')") public ZoneResponse activateZone(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setZoneActive(id,true,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/zones/{id}/deactivate") @PreAuthorize("hasRole('ADMIN')") public ZoneResponse deactivateZone(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setZoneActive(id,false,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/rooms") public PageResponse<RoomResponse> rooms(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listRooms(page(page,size,"createdAt")));}
    @GetMapping("/rooms/{id}") public RoomResponse room(@PathVariable UUID id){return service.getRoom(id);}
    @PostMapping("/rooms") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public RoomResponse createRoom(@Valid @RequestBody RoomRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createRoom(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/rooms/{id}") @PreAuthorize("hasRole('ADMIN')") public RoomResponse updateRoom(@PathVariable UUID id,@Valid @RequestBody RoomRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateRoom(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/rooms/{id}/activate") @PreAuthorize("hasRole('ADMIN')") public RoomResponse activateRoom(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setRoomActive(id,true,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/rooms/{id}/deactivate") @PreAuthorize("hasRole('ADMIN')") public RoomResponse deactivateRoom(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setRoomActive(id,false,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/endpoints") public PageResponse<EndpointResponse> endpoints(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listEndpoints(page(page,size,"createdAt")));}
    @GetMapping("/endpoints/{id}") public EndpointResponse endpoint(@PathVariable UUID id){return service.getEndpoint(id);}
    @PostMapping("/endpoints") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public EndpointResponse createEndpoint(@Valid @RequestBody EndpointRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createEndpoint(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/endpoints/{id}") @PreAuthorize("hasRole('ADMIN')") public EndpointResponse updateEndpoint(@PathVariable UUID id,@Valid @RequestBody EndpointRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateEndpoint(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/endpoints/{id}/activate") @PreAuthorize("hasRole('ADMIN')") public EndpointResponse activateEndpoint(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setEndpointActive(id,true,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/endpoints/{id}/deactivate") @PreAuthorize("hasRole('ADMIN')") public EndpointResponse deactivateEndpoint(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setEndpointActive(id,false,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/endpoint-groups") public PageResponse<EndpointGroupResponse> groups(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listEndpointGroups(page(page,size,"createdAt")));}
    @GetMapping("/endpoint-groups/{id}") public EndpointGroupResponse group(@PathVariable UUID id){return service.getEndpointGroup(id);}
    @PostMapping("/endpoint-groups") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public EndpointGroupResponse createGroup(@Valid @RequestBody EndpointGroupRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createEndpointGroup(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/endpoint-groups/{id}") @PreAuthorize("hasRole('ADMIN')") public EndpointGroupResponse updateGroup(@PathVariable UUID id,@Valid @RequestBody EndpointGroupRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateEndpointGroup(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/endpoint-groups/{id}/activate") @PreAuthorize("hasRole('ADMIN')") public EndpointGroupResponse activateGroup(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setEndpointGroupActive(id,true,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/endpoint-groups/{id}/deactivate") @PreAuthorize("hasRole('ADMIN')") public EndpointGroupResponse deactivateGroup(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.setEndpointGroupActive(id,false,a.accountId(),RequestMetadataFactory.from(r));}

    @GetMapping("/endpoint-group-members") public PageResponse<EndpointGroupMemberResponse> members(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.listEndpointGroupMembers(page(page,size,"createdAt")));}
    @PostMapping("/endpoint-group-members") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public EndpointGroupMemberResponse createMember(@Valid @RequestBody EndpointGroupMemberRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createEndpointGroupMember(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/endpoint-group-members/{groupId}/{endpointId}") @PreAuthorize("hasRole('ADMIN')") public EndpointGroupMemberResponse updateMember(@PathVariable UUID groupId,@PathVariable UUID endpointId,@Valid @RequestBody EndpointGroupMemberRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.updateEndpointGroupMember(groupId,endpointId,b,a.accountId(),RequestMetadataFactory.from(r));}
    @DeleteMapping("/endpoint-group-members/{groupId}/{endpointId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')") public void removeMember(@PathVariable UUID groupId,@PathVariable UUID endpointId,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){service.removeEndpointGroupMember(groupId,endpointId,a.accountId(),RequestMetadataFactory.from(r));}

    private Pageable page(int page,int size,String property){return PageRequest.of(page,size,Sort.by(property).ascending());}
}
