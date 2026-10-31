package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.facility.response.PublicFacilityResponses.*;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.ZoneType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PublicFacilityCatalogService {
    Page<Hospital> hospitals(String q, String category, String languageCode, Pageable pageable);
    Hospital hospital(UUID id);
    Page<Article> articles(UUID hospitalId, String q, String category, String languageCode, Pageable pageable);
    Article article(UUID hospitalId, UUID articleId);
    Page<Floor> floors(UUID hospitalInformationId, String q, Pageable pageable);
    Floor floor(UUID id);
    Page<Zone> zones(UUID mapId, ZoneType zoneType, String q, Pageable pageable);
    Zone zone(UUID id);
    Page<Room> rooms(UUID floorId, String roomType, String departmentCode, String q, Pageable pageable);
    Room room(UUID id);
    Page<Endpoint> endpoints(UUID mapId, UUID roomId, EndpointType endpointType, Boolean accessible,
                             String q, Pageable pageable);
    Endpoint endpoint(UUID id);
    Page<EndpointGroup> endpointGroups(UUID mapId, String q, Pageable pageable);
    EndpointGroup endpointGroup(UUID id);
    Page<Endpoint> endpointGroupMembers(UUID groupId, Pageable pageable);
}
