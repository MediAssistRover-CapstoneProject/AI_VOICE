package com.mediassistrover.backend.dto.facility.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.ZoneType;

import java.time.Instant;
import java.util.UUID;

/** DTOs intentionally limited to fields safe for the anonymous patient catalog. */
public final class PublicFacilityResponses {
    private PublicFacilityResponses() { }

    public record Hospital(UUID hospitalInformationId, String infoCategory, String title, String content,
                           String languageCode, JsonNode keywordsJson, Instant effectiveFrom,
                           Instant effectiveUntil) { }
    public record Article(UUID articleId, UUID hospitalInformationId, String articleCode, String category,
                          String canonicalQuestion, String title, String answer, String languageCode,
                          JsonNode keywordsJson, Instant effectiveFrom, Instant effectiveUntil) { }
    public record Floor(UUID floorId, UUID hospitalInformationId, String floorCode, String floorName,
                        Integer levelNo, int displayOrder, String description) { }
    public record Zone(UUID zoneId, UUID mapId, String zoneCode, String zoneName, ZoneType zoneType,
                       String description) { }
    public record Room(UUID roomId, UUID floorId, UUID defaultEndpointGroupId, String roomCode,
                       String roomName, String roomType, String departmentCode, String description) { }
    public record Endpoint(UUID endpointId, UUID mapId, UUID zoneId, UUID roomId, String endpointCode,
                           String endpointName, EndpointType endpointType, boolean accessible) { }
    public record EndpointGroup(UUID endpointGroupId, UUID mapId, String groupCode, String groupName,
                                String description) { }
}
