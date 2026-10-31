package com.mediassistrover.backend.controller.pi;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.NavigationProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/pi") @PreAuthorize("hasRole('DEVICE')") @RequiredArgsConstructor
public class NavigationProfileController {
    private final NavigationProfileService profiles;
    @GetMapping("/catalog/navigation-profile") public NavigationProfileService.Manifest manifest(@AuthenticationPrincipal DevicePrincipal device) {return profiles.manifest(device);}
    @PostMapping("/catalog/navigation-profile/ack") public NavigationProfileService.Receipt confirm(@Valid @RequestBody NavigationProfileService.Confirm body,@AuthenticationPrincipal DevicePrincipal device) {return profiles.confirm(body,device);}
    @GetMapping("/maps/{mapId}/zones/config") public JsonNode zones(@PathVariable UUID mapId,@AuthenticationPrincipal DevicePrincipal device) {return profiles.zoneConfiguration(mapId,device);}
}
