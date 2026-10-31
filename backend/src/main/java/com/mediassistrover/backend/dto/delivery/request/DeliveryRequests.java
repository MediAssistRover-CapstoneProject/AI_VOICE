package com.mediassistrover.backend.dto.delivery.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryRequests {
    private DeliveryRequests() { }
    public record Item(@NotBlank @Size(max=80) String itemCode,@NotBlank @Size(max=160) String itemName,
                       @NotBlank @Size(max=64) String itemCategory,@Min(1) int quantity,
                       @NotBlank @Size(max=32) String unit,boolean sensitive,
                       @Size(max=4000) String handlingInstruction,@Size(max=4000) String itemNote) { }
    @Schema(name="DeliveryCreateRequest") public record Create(@NotNull UUID mapId,UUID pickupRoomId,UUID deliveryRoomId,
                         @NotNull UUID pickupEndpointId,UUID deliveryEndpointId,UUID deliveryGroupId,
                         @NotNull TargetMode targetMode,@NotBlank @Size(max=160) String recipientName,
                         @Size(max=100) String recipientContact,@AssertTrue boolean requiresConfirmation,
                         @Size(max=4000) String temperatureRequirement,@Future Instant deliveryDeadline,
                         @Size(max=4000) String deliveryNote,@NotEmpty List<@Valid Item> items,
                         @Min(0) @Max(100) int priority,@Size(max=4000) String note,JsonNode data) { }
    public record ConfirmedItem(@NotBlank @Size(max=80) String itemCode,@Min(1) int quantity) { }
    public record Confirm(@NotNull UUID verificationId,@NotNull UUID jobStepId,
                          @NotEmpty List<@Valid ConfirmedItem> items,
                          @AssertTrue boolean compartmentClosed,@AssertTrue boolean compartmentLocked,
                          @Size(max=1000) String note) {
        public Confirm(UUID verificationId,String note){this(verificationId,null,null,false,false,note);}
    }
}
