package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.facility.response.PublicFacilityResponses.*;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.enums.ZoneType;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.mapper.FacilityMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.PublicFacilityCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicFacilityCatalogServiceImpl implements PublicFacilityCatalogService {
    private final HospitalInformationRepository hospitals;
    private final HospitalInformationArticleRepository articles;
    private final FloorRepository floors;
    private final ZoneRepository zones;
    private final RoomRepository rooms;
    private final EndpointRepository endpoints;
    private final EndpointGroupRepository groups;
    private final EndpointGroupMemberRepository members;
    private final FacilityMapper mapper;
    private final Clock clock;

    @Override public Page<Hospital> hospitals(String q, String category, String languageCode, Pageable p) {
        return hospitals.searchPublic(HospitalInformationStatus.PUBLISHED, now(), clean(q), clean(category), clean(languageCode), p).map(mapper::toPublicResponse);
    }
    @Override public Hospital hospital(UUID id) { return mapper.toPublicResponse(hospitals.findPublicById(id, HospitalInformationStatus.PUBLISHED, now()).orElseThrow(() -> missing("Hospital information", id))); }
    @Override public Page<Article> articles(UUID hospitalId, String q, String category, String languageCode, Pageable p) {
        hospital(hospitalId);
        return articles.searchPublic(hospitalId, HospitalInformationStatus.PUBLISHED, now(), clean(q), clean(category), clean(languageCode), p).map(mapper::toPublicResponse);
    }
    @Override public Article article(UUID hospitalId, UUID articleId) { return mapper.toPublicResponse(articles.findPublicById(hospitalId, articleId, HospitalInformationStatus.PUBLISHED, now()).orElseThrow(() -> missing("Article", articleId))); }
    @Override public Page<Floor> floors(UUID hospitalId, String q, Pageable p) { return floors.searchPublic(hospitalId, clean(q), now(), p).map(mapper::toPublicResponse); }
    @Override public Floor floor(UUID id) { return mapper.toPublicResponse(floors.findPublicById(id, now()).orElseThrow(() -> missing("Floor", id))); }
    @Override public Page<Zone> zones(UUID mapId, ZoneType type, String q, Pageable p) { return zones.searchPublic(mapId, type, clean(q), p).map(mapper::toPublicResponse); }
    @Override public Zone zone(UUID id) { return mapper.toPublicResponse(zones.findByZoneIdAndActiveTrueAndPublicVisibleTrue(id).orElseThrow(() -> missing("Zone", id))); }
    @Override public Page<Room> rooms(UUID floorId, String type, String department, String q, Pageable p) { return rooms.searchPublic(floorId, clean(type), clean(department), clean(q), p).map(mapper::toPublicResponse); }
    @Override public Room room(UUID id) { return mapper.toPublicResponse(rooms.findByRoomIdAndActiveTrueAndPublicVisibleTrue(id).orElseThrow(() -> missing("Room", id))); }
    @Override public Page<Endpoint> endpoints(UUID mapId, UUID roomId, EndpointType type, Boolean accessible, String q, Pageable p) { return endpoints.searchPublic(mapId, roomId, type, accessible, clean(q), p).map(mapper::toPublicResponse); }
    @Override public Endpoint endpoint(UUID id) { return mapper.toPublicResponse(endpoints.findByEndpointIdAndActiveTrueAndPublicVisibleTrue(id).orElseThrow(() -> missing("Endpoint", id))); }
    @Override public Page<EndpointGroup> endpointGroups(UUID mapId, String q, Pageable p) { return groups.searchPublic(mapId, clean(q), p).map(mapper::toPublicResponse); }
    @Override public EndpointGroup endpointGroup(UUID id) { return mapper.toPublicResponse(groups.findPublicById(id).orElseThrow(() -> missing("Endpoint group", id))); }
    @Override public Page<Endpoint> endpointGroupMembers(UUID groupId, Pageable p) { endpointGroup(groupId); return members.findPublicMembers(groupId, p).map(member -> mapper.toPublicResponse(member.getEndpoint())); }

    private Instant now() { return Instant.now(clock); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ResourceNotFoundException missing(String type, UUID id) { return new ResourceNotFoundException(type + " not found: " + id); }
}
