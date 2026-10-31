package com.mediassistrover.backend.dto.delivery.response;

import com.mediassistrover.backend.dto.request.response.ServiceRequestResponses.ServiceRequestResponse;
import com.mediassistrover.backend.enums.TargetMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryResponses {
    private DeliveryResponses() { }
    public record ItemResponse(UUID deliveryItemId,String itemCode,String itemName,String itemCategory,int quantity,
                               String unit,boolean sensitive,String handlingInstruction,String itemNote) { }
    public record DeliveryResponse(ServiceRequestResponse request,UUID mapId,UUID pickupRoomId,UUID deliveryRoomId,
                                   UUID pickupEndpointId,UUID deliveryEndpointId,UUID deliveryGroupId,
                                   UUID resolvedDeliveryEndpointId,UUID senderAccountId,UUID receiverAccountId,
                                   TargetMode targetMode,String recipientName,String recipientContact,
                                   boolean requiresConfirmation,String temperatureRequirement,Instant deliveryDeadline,
                                   Instant pickedUpAt,Instant deliveredAt,Instant pickupCompartmentConfirmedAt,
                                   Instant deliveryCompartmentConfirmedAt,Instant pickupPayloadConfirmedAt,
                                   Instant deliveryPayloadConfirmedAt,String deliveryNote,long rowVersion,
                                   List<ItemResponse> items) { }
}
