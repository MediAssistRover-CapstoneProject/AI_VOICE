package com.mediassistrover.backend.controller.pi;

import com.mediassistrover.backend.dto.pi.request.DeviceAuthRequest;
import com.mediassistrover.backend.dto.pi.response.PiResponses.DeviceTokenResponse;
import com.mediassistrover.backend.service.DeviceAuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/pi/auth") @RequiredArgsConstructor
@Tag(name="Pi Device Authentication")
public class DeviceAuthController {
    private final DeviceAuthService service;
    @PostMapping("/token") public DeviceTokenResponse token(@Valid @RequestBody DeviceAuthRequest request){return service.authenticate(request.robotCode(),request.deviceSecret());}
}
