package com.mediassistrover.backend.controller.pi;

import com.mediassistrover.backend.config.OpenApiConfig;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.vision.VisionDtos.*;
import com.mediassistrover.backend.vision.VisionEventService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pi/vision")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')")
@SecurityRequirement(name=OpenApiConfig.DEVICE_BEARER_SCHEME)
@Tag(name="Server–Pi Vision", description="Device-authenticated fall observations and staff alerts")
public class PiVisionController {
    private final VisionEventService service;

    @PostMapping("/detections")
    public DetectionReceipt detection(@Valid @RequestBody DetectionRequest request,
                                      @AuthenticationPrincipal DevicePrincipal principal) {
        return service.record(request, principal);
    }
}
