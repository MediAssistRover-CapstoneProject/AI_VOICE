package com.mediassistrover.backend.controller.pi;

import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/mqtt-rollout")
@PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
public class MqttRolloutController {
    private final MqttRolloutService rollout;
    private final MqttOperationsStatusService operations;
    @GetMapping public MqttOperationsStatusService.Status status() { return operations.status(); }
    @PutMapping public MqttRolloutService.State change(@Valid @RequestBody MqttRolloutService.Change input,
                                                     @AuthenticationPrincipal AccountPrincipal actor) { return rollout.change(input,actor); }
}
