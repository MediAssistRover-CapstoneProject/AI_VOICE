package com.mediassistrover.backend.dto.alert.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.time.Instant;
import java.util.*;

public final class AlertResponses {
    private AlertResponses() { }
    public record RecipientResponse(UUID alertRecipientId,UUID accountId,AlertChannel channel,
                                    AlertDeliveryStatus deliveryStatus,Instant sentAt,Instant deliveredAt,
                                    Instant acknowledgedAt,String errorCode) { }
    public record AlertResponse(UUID alertId,UUID incidentId,UUID jobStepId,UUID serviceRequestId,UUID zoneId,
                                String notificationType,AlertSeverity severity,AlertStatus status,String message,
                                JsonNode dataJson,boolean requireAck,AlertAckPolicy ackPolicy,Instant createdAt,
                                Instant expiresAt,long rowVersion,List<RecipientResponse> recipients) { }
}
