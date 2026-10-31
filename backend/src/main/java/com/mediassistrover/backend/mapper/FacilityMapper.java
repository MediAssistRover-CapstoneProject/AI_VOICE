package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.facility.request.FacilityRequests.*;
import com.mediassistrover.backend.dto.facility.response.FacilityResponses.*;
import com.mediassistrover.backend.dto.facility.response.PublicFacilityResponses;
import com.mediassistrover.backend.entity.*;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FacilityMapper {
    @Mapping(target = "updatedByAccountId", source = "updatedBy.accountId")
    HospitalInformationResponse toResponse(HospitalInformation entity);
    @Mapping(target = "hospitalInformationId", source = "hospitalInformation.hospitalInformationId")
    @Mapping(target = "updatedByAccountId", source = "updatedBy.accountId")
    HospitalInformationArticleResponse toResponse(HospitalInformationArticle entity);
    @Mapping(target = "hospitalInformationId", source = "hospitalInformation.hospitalInformationId")
    FloorResponse toResponse(Floor entity);
    @Mapping(target = "floorId", source = "floor.floorId") MapResponse toResponse(FacilityMap entity);
    @Mapping(target = "mapId", source = "map.mapId") ZoneResponse toResponse(Zone entity);
    @Mapping(target = "floorId", source = "floor.floorId")
    @Mapping(target = "defaultEndpointGroupId", source = "defaultEndpointGroup.endpointGroupId") RoomResponse toResponse(Room entity);
    @Mapping(target = "mapId", source = "map.mapId") @Mapping(target = "zoneId", source = "zone.zoneId")
    @Mapping(target = "roomId", source = "room.roomId")
    @Mapping(target = "xM", expression = "java(entity.getXM())")
    @Mapping(target = "yM", expression = "java(entity.getYM())")
    EndpointResponse toResponse(Endpoint entity);
    @Mapping(target = "mapId", source = "map.mapId") EndpointGroupResponse toResponse(EndpointGroup entity);
    @Mapping(target = "endpointGroupId", source = "endpointGroup.endpointGroupId")
    @Mapping(target = "endpointId", source = "endpoint.endpointId") @Mapping(target = "mapId", source = "map.mapId")
    EndpointGroupMemberResponse toResponse(EndpointGroupMember entity);

    @Mapping(target = "hospitalInformationId", source = "hospitalInformation.hospitalInformationId")
    PublicFacilityResponses.Floor toPublicResponse(Floor entity);
    @Mapping(target = "mapId", source = "map.mapId")
    PublicFacilityResponses.Zone toPublicResponse(Zone entity);
    @Mapping(target = "floorId", source = "floor.floorId")
    @Mapping(target = "defaultEndpointGroupId", source = "defaultEndpointGroup.endpointGroupId")
    PublicFacilityResponses.Room toPublicResponse(Room entity);
    @Mapping(target = "mapId", source = "map.mapId")
    @Mapping(target = "zoneId", source = "zone.zoneId")
    @Mapping(target = "roomId", source = "room.roomId")
    PublicFacilityResponses.Endpoint toPublicResponse(Endpoint entity);
    @Mapping(target = "mapId", source = "map.mapId")
    PublicFacilityResponses.EndpointGroup toPublicResponse(EndpointGroup entity);
    PublicFacilityResponses.Hospital toPublicResponse(HospitalInformation entity);
    @Mapping(target = "hospitalInformationId", source = "hospitalInformation.hospitalInformationId")
    PublicFacilityResponses.Article toPublicResponse(HospitalInformationArticle entity);

    @Mapping(target = "status", constant = "DRAFT") @Mapping(target = "updatedBy", ignore = true)
    HospitalInformation toEntity(HospitalInformationRequest request);
    @Mapping(target = "status", constant = "DRAFT")
    @Mapping(target = "hospitalInformation", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    HospitalInformationArticle toEntity(HospitalInformationArticleRequest request);
    @Mapping(target = "hospitalInformation", ignore = true) Floor toEntity(FloorRequest request);
    @Mapping(target = "floor", ignore = true) @Mapping(target = "status", constant = "DRAFT") FacilityMap toEntity(MapRequest request);
    @Mapping(target = "map", ignore = true) Zone toEntity(ZoneRequest request);
    @Mapping(target = "floor", ignore = true) @Mapping(target = "defaultEndpointGroup", ignore = true) Room toEntity(RoomRequest request);
    @Mapping(target = "map", ignore = true) @Mapping(target = "zone", ignore = true) @Mapping(target = "room", ignore = true)
    Endpoint toEntity(EndpointRequest request);
    @Mapping(target = "map", ignore = true) EndpointGroup toEntity(EndpointGroupRequest request);

    @Mapping(target = "hospitalInformationId", ignore = true) @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "status", ignore = true) @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(HospitalInformationRequest request, @MappingTarget HospitalInformation entity);
    @Mapping(target = "articleId", ignore = true)
    @Mapping(target = "hospitalInformation", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "rowVersion", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void update(HospitalInformationArticleRequest request, @MappingTarget HospitalInformationArticle entity);
    @Mapping(target = "floorId", ignore = true) @Mapping(target = "hospitalInformation", ignore = true)
    @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(FloorRequest request, @MappingTarget Floor entity);
    @Mapping(target = "mapId", ignore = true) @Mapping(target = "floor", ignore = true) @Mapping(target = "status", ignore = true)
    @Mapping(target = "commissionedAt", ignore = true) @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(MapRequest request, @MappingTarget FacilityMap entity);
    @Mapping(target = "zoneId", ignore = true) @Mapping(target = "map", ignore = true)
    @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(ZoneRequest request, @MappingTarget Zone entity);
    @Mapping(target = "roomId", ignore = true) @Mapping(target = "floor", ignore = true) @Mapping(target = "defaultEndpointGroup", ignore = true)
    @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(RoomRequest request, @MappingTarget Room entity);
    @Mapping(target = "endpointId", ignore = true) @Mapping(target = "map", ignore = true) @Mapping(target = "zone", ignore = true)
    @Mapping(target = "room", ignore = true) @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(EndpointRequest request, @MappingTarget Endpoint entity);
    @Mapping(target = "endpointGroupId", ignore = true) @Mapping(target = "map", ignore = true)
    @Mapping(target = "createdAt", ignore = true) @Mapping(target = "updatedAt", ignore = true)
    void update(EndpointGroupRequest request, @MappingTarget EndpointGroup entity);
}
