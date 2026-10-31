package com.mediassistrover.backend.controller.pi;
import com.mediassistrover.backend.config.OpenApiConfig;
import com.mediassistrover.backend.dto.emergency.request.EmergencyRequests.SosPayload;
import com.mediassistrover.backend.dto.emergency.response.EmergencyResponses.SosResponse;
import com.mediassistrover.backend.dto.emergency.response.EmergencyResponses.DeviceIncidentStatusResponse;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.EmergencyService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/pi/incidents") @RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')") @Tag(name="Robot Emergency")
@SecurityRequirement(name=OpenApiConfig.DEVICE_BEARER_SCHEME)
public class PiEmergencyController {
    private final EmergencyService service;
    @PostMapping("/sos") public SosResponse sos(@Valid @RequestBody PiEnvelope<SosPayload> envelope,@AuthenticationPrincipal DevicePrincipal actor,HttpServletRequest request){return service.createSos(envelope,actor,RequestMetadataFactory.from(request));}
    @GetMapping("/{incidentId}") public DeviceIncidentStatusResponse get(@PathVariable UUID incidentId,@AuthenticationPrincipal DevicePrincipal actor){return service.getDeviceIncident(incidentId,actor);}
}
