package com.mediassistrover.backend.controller.monitoring;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.ai.AiSensingService;
import com.mediassistrover.backend.security.AccountPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/admin/monitoring/robots") @PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
public class AiSensingProfileController {
    private final AiSensingService service;
    @PutMapping("/{robotId}/ai-profile") public JsonNode configure(@PathVariable UUID robotId,@Valid @RequestBody AiSensingService.Configure input,@AuthenticationPrincipal AccountPrincipal actor){return service.configure(robotId,input,actor);}
}
