package com.mediassistrover.backend.controller.delivery;
import com.mediassistrover.backend.dto.delivery.request.DeliveryRequests.*;
import com.mediassistrover.backend.dto.delivery.response.DeliveryResponses.DeliveryResponse;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.enums.ServiceRequestStatus;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.DeliveryService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.time.Instant;
@RestController @RequestMapping("/api/v1/delivery-requests") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Delivery Requests")
public class DeliveryController {
    private final DeliveryService service;
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public DeliveryResponse create(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Create body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.create(body,key,actor,RequestMetadataFactory.from(request));}
    @GetMapping public PageResponse<DeliveryResponse> list(@RequestParam(required=false)ServiceRequestStatus status,@RequestParam(required=false)UUID senderId,@RequestParam(required=false)UUID receiverId,@RequestParam(required=false)UUID pickupRoomId,@RequestParam(required=false)UUID deliveryRoomId,@RequestParam(required=false)Instant deadlineFrom,@RequestParam(required=false)Instant deadlineTo,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,@AuthenticationPrincipal AccountPrincipal actor){return PageResponse.from(service.list(status,senderId,receiverId,pickupRoomId,deliveryRoomId,deadlineFrom,deadlineTo,from,to,actor,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"serviceRequest.requestedAt"))));}
    @GetMapping("/{id}") public DeliveryResponse get(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor){return service.get(id,actor);}
    @PostMapping("/{id}/confirm-pickup") public DeliveryResponse pickup(@PathVariable UUID id,@Valid @RequestBody Confirm body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.confirmPickup(id,body,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/confirm-delivery") public DeliveryResponse delivery(@PathVariable UUID id,@Valid @RequestBody Confirm body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.confirmDelivery(id,body,actor,RequestMetadataFactory.from(request));}
}
