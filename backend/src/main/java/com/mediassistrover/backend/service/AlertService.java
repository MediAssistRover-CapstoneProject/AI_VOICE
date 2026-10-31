package com.mediassistrover.backend.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.dto.alert.response.AlertResponses.AlertResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.UUID;
public interface AlertService {
    Alert createSystemAlert(String operationKey,String type,AlertSeverity severity,String message,JsonNode data,
                            EmergencyIncident incident,ServiceRequest request,JobStep step,Zone zone,
                            boolean requireAck,AlertAckPolicy policy,Instant expiresAt);
    Alert createSystemAlert(String operationKey,String type,AlertSeverity severity,String message,JsonNode data,
                            EmergencyIncident incident,ServiceRequest request,JobStep step,Zone zone,
                            boolean requireAck,AlertAckPolicy policy,Instant expiresAt,JsonNode recipients);
    AlertResponse get(UUID id,AccountPrincipal actor);
    Page<AlertResponse> list(AlertStatus status,AlertSeverity severity,String type,Instant from,Instant to,
                             AccountPrincipal actor,Pageable pageable);
    AlertResponse acknowledge(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    AlertResponse resolve(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    AlertResponse close(UUID id,AccountPrincipal actor,RequestMetadata metadata);
}
