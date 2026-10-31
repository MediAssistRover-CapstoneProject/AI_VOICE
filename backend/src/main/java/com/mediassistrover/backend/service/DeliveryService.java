package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.delivery.request.DeliveryRequests.*;
import com.mediassistrover.backend.dto.delivery.response.DeliveryResponses.DeliveryResponse;
import com.mediassistrover.backend.security.AccountPrincipal;
import java.util.UUID;
import java.time.Instant;
import com.mediassistrover.backend.enums.ServiceRequestStatus;
import org.springframework.data.domain.*;
public interface DeliveryService {
    DeliveryResponse create(Create request,String idempotencyKey,AccountPrincipal actor,RequestMetadata metadata);
    DeliveryResponse get(UUID id,AccountPrincipal actor);
    Page<DeliveryResponse> list(ServiceRequestStatus status,UUID senderId,UUID receiverId,UUID pickupRoomId,
                                UUID deliveryRoomId,Instant deadlineFrom,Instant deadlineTo,Instant from,Instant to,
                                AccountPrincipal actor,Pageable pageable);
    DeliveryResponse confirmPickup(UUID id,Confirm request,AccountPrincipal actor,RequestMetadata metadata);
    DeliveryResponse confirmDelivery(UUID id,Confirm request,AccountPrincipal actor,RequestMetadata metadata);
}
