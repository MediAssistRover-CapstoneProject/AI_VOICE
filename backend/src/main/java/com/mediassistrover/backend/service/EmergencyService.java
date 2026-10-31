package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.emergency.request.EmergencyRequests.*;
import com.mediassistrover.backend.dto.emergency.response.EmergencyResponses.*;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.enums.EmergencyIncidentStatus;
import com.mediassistrover.backend.security.*;
import org.springframework.data.domain.*;
import java.util.UUID;
import java.time.Instant;
import com.mediassistrover.backend.enums.EmergencyIncidentSeverity;
public interface EmergencyService {
    SosResponse createSos(PiEnvelope<SosPayload> envelope,DevicePrincipal actor,RequestMetadata metadata);
    DeviceIncidentStatusResponse getDeviceIncident(UUID id,DevicePrincipal actor);
    IncidentResponse get(UUID id,AccountPrincipal actor);
    Page<IncidentResponse> list(EmergencyIncidentStatus status,EmergencyIncidentSeverity severity,String type,
                                UUID mapId,UUID roomId,Instant from,Instant to,AccountPrincipal actor,Pageable pageable);
    IncidentResponse acknowledge(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    IncidentResponse start(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    IncidentResponse resolve(UUID id,Resolution request,AccountPrincipal actor,RequestMetadata metadata);
    IncidentResponse close(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    IncidentResponse falseAlarm(UUID id,Resolution request,AccountPrincipal actor,RequestMetadata metadata);
}
