package com.mediassistrover.backend.controller.pi;
import com.mediassistrover.backend.config.OpenApiConfig;
import com.mediassistrover.backend.dto.monitoring.request.MonitoringRequests.*;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.response.PiResponses.AckResponse;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.MonitoringService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/pi/monitoring") @RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')") @SecurityRequirement(name=OpenApiConfig.DEVICE_BEARER_SCHEME)
@Tag(name="Server–Pi Monitoring",description="Append-only sensing event ingestion; Backend does not run AI algorithms")
public class PiMonitoringController {
    private final MonitoringService service;
    @PostMapping("/detections") public AckResponse detection(@Valid @RequestBody PiEnvelope<DetectionPayload> envelope,@AuthenticationPrincipal DevicePrincipal principal){return service.recordDetection(envelope,principal);}
    @PostMapping("/crowd-density") public AckResponse crowdDensity(@Valid @RequestBody PiEnvelope<CrowdDensityPayload> envelope,@AuthenticationPrincipal DevicePrincipal principal){return service.recordCrowdDensity(envelope,principal);}
}
