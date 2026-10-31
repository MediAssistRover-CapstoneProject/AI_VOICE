package com.mediassistrover.backend.controller.pi;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.service.NavigationSessionService;
import com.mediassistrover.backend.security.DevicePrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@RestController @RequiredArgsConstructor @Tag(name="Navigation test session")
public class NavigationSessionController {
    private final NavigationSessionService sessions;
    @GetMapping("/api/v1/robots/{robotId}/navigation-session") @PreAuthorize("hasRole('ADMIN')")
    public ObjectNode status(@PathVariable UUID robotId){return sessions.status(robotId);}
    @PostMapping("/api/v1/robots/{robotId}/navigation-session") @PreAuthorize("hasRole('ADMIN')")
    public ObjectNode create(@PathVariable UUID robotId,@Valid @RequestBody NavigationSessionService.Create body){return sessions.create(robotId,body);}
    @PostMapping("/api/v1/robots/{robotId}/navigation-session/initialization") @PreAuthorize("hasRole('ADMIN')")
    public ObjectNode initialize(@PathVariable UUID robotId,@Valid @RequestBody NavigationSessionService.Initialize body){return sessions.initialize(robotId,body);}
    @PostMapping("/api/v1/robots/{robotId}/navigation-session/close") @PreAuthorize("hasRole('ADMIN')")
    public ObjectNode close(@PathVariable UUID robotId,@Valid @RequestBody NavigationSessionService.Close body){return sessions.close(robotId,body.sessionId());}
    @GetMapping("/api/v1/pi/navigation-session") @PreAuthorize("hasRole('DEVICE')")
    public ObjectNode device(@AuthenticationPrincipal DevicePrincipal device){return sessions.device(device);}
    @PostMapping("/api/v1/pi/navigation-session/close") @PreAuthorize("hasRole('DEVICE')")
    public ObjectNode closeDevice(@AuthenticationPrincipal DevicePrincipal device,@Valid @RequestBody NavigationSessionService.Close body){return sessions.closeDevice(device,body.sessionId());}
}
