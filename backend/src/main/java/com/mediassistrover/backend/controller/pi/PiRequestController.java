package com.mediassistrover.backend.controller.pi;
import com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.*;
import com.mediassistrover.backend.dto.request.response.ServiceRequestResponses.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.ServiceRequestService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.mediassistrover.backend.config.OpenApiConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/pi/requests") @RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')") @Tag(name="Robot Screen Requests")
@SecurityRequirement(name=OpenApiConfig.DEVICE_BEARER_SCHEME)
public class PiRequestController {
    private final ServiceRequestService service;
    @PostMapping("/information") public ServiceRequestResponse information(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody InformationCreateRequest body,@AuthenticationPrincipal DevicePrincipal actor,HttpServletRequest request){return service.createDeviceInformation(body,key,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/navigation") public NavigationResponse navigation(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody NavigationCreateRequest body,@AuthenticationPrincipal DevicePrincipal actor,HttpServletRequest request){return service.createDeviceNavigation(body,key,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/escort") public EscortResponse escort(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody EscortCreateRequest body,@AuthenticationPrincipal DevicePrincipal actor,HttpServletRequest request){return service.createDeviceEscort(body,key,actor,RequestMetadataFactory.from(request));}
    @GetMapping("/{requestId}") public DeviceRequestDetailResponse get(@PathVariable UUID requestId,@AuthenticationPrincipal DevicePrincipal actor){return service.getDeviceRequest(requestId,actor);}
    @PostMapping("/{requestId}/cancel") public DeviceRequestDetailResponse cancel(@PathVariable UUID requestId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CancelRequest body,
            @AuthenticationPrincipal DevicePrincipal actor,HttpServletRequest request){return service.cancelDeviceRequest(requestId,body.reason(),key,actor,RequestMetadataFactory.from(request));}
}
