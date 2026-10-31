package com.mediassistrover.backend.dto.facility.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class FacilityResponses {
    private FacilityResponses() { }

    public record HospitalInformationResponse(UUID hospitalInformationId, UUID updatedByAccountId,
            String infoCategory, String title, String content, String languageCode, JsonNode keywordsJson,
            HospitalInformationStatus status, boolean publicVisible, Instant effectiveFrom, Instant effectiveUntil,
            Instant createdAt, Instant updatedAt) { }
    public record HospitalInformationArticleResponse(UUID articleId, UUID hospitalInformationId,
            UUID updatedByAccountId, String articleCode, String category, String canonicalQuestion,
            String title, String answer, String languageCode, JsonNode keywordsJson,
            HospitalInformationStatus status, boolean publicVisible, Instant effectiveFrom,
            Instant effectiveUntil, long rowVersion, Instant createdAt, Instant updatedAt) { }
    public record FloorResponse(UUID floorId, UUID hospitalInformationId, String floorCode, String floorName,
            Integer levelNo, int displayOrder, String description, boolean active, Instant createdAt, Instant updatedAt) { }
    public record MapResponse(UUID mapId, UUID floorId, String mapCode, int versionNo, String mapName,
            BigDecimal widthM, BigDecimal heightM, BigDecimal resolutionMPerPixel, BigDecimal originXM,
            BigDecimal originYM, BigDecimal originYawRad, String frameId, String mapFileUri, String assetHash,
            MapStatus status, Instant commissionedAt, Instant createdAt, Instant updatedAt) { }
    public record MapArtifactResponse(UUID mapId, String mapCode, int versionNo, String mapFileUri,
            String assetHash, String frameId, MapStatus status) { }
    public record ZoneResponse(UUID zoneId, UUID mapId, String zoneCode, String zoneName, ZoneType zoneType,
            JsonNode polygonJson, BigDecimal speedLimitMps, Integer capacityLimit, BigDecimal calibratedAreaM2,
            String cameraSourceRef, JsonNode cameraRoiJson, boolean active, boolean publicVisible,
            String description, Instant createdAt, Instant updatedAt) { }
    public record RoomResponse(UUID roomId, UUID floorId, UUID defaultEndpointGroupId, String roomCode,
            String roomName, String roomType, String departmentCode, String description, boolean active,
            boolean publicVisible, Instant createdAt, Instant updatedAt) { }
    public record EndpointResponse(UUID endpointId, UUID mapId, UUID zoneId, UUID roomId, String endpointCode,
            String endpointName, EndpointType endpointType, BigDecimal xM, BigDecimal yM, BigDecimal yawRad,
            boolean active, boolean accessible, boolean publicVisible, String description,
            Instant createdAt, Instant updatedAt) { }
    public record EndpointGroupResponse(UUID endpointGroupId, UUID mapId, String groupCode, String groupName,
            SelectionPolicy selectionPolicy, boolean allowFallback, String description, boolean active,
            Instant createdAt, Instant updatedAt) { }
    public record EndpointGroupMemberResponse(UUID endpointGroupId, UUID endpointId, UUID mapId,
            int sequenceNo, boolean active, Instant createdAt) { }
}
