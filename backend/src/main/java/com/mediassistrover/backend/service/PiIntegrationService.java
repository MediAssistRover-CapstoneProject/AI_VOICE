package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.response.PiResponses.*;
import com.mediassistrover.backend.security.DevicePrincipal;

import java.util.UUID;

public interface PiIntegrationService {
    AckResponse heartbeat(PiEnvelope<HeartbeatPayload> envelope,DevicePrincipal principal);
    AckResponse location(PiEnvelope<LocationPayload> envelope,DevicePrincipal principal);
    CatalogManifestResponse catalogManifest(DevicePrincipal principal);
    PiMapMetadata mapMetadata(UUID mapId,DevicePrincipal principal);
}
