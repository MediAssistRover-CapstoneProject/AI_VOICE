package com.mediassistrover.backend.controller.job;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.dto.job.request.MqttOperationRecoveryRequest;
import com.mediassistrover.backend.mqtt.MqttOperationRecoveryService;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/jobs") @RequiredArgsConstructor @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
public class MqttOperationRecoveryController {
    private final MqttOperationRecoveryService service;
    @GetMapping("/{jobId}/operations/recovery")
    public JsonNode evidence(@PathVariable UUID jobId,@AuthenticationPrincipal AccountPrincipal actor){return service.evidence(jobId,actor);}
    @PostMapping("/{jobId}/operations/{jobStepId}/reconcile")
    public JsonNode reconcile(@PathVariable UUID jobId,@PathVariable UUID jobStepId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody MqttOperationRecoveryRequest request,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest http){
        return service.reconcile(jobId,jobStepId,key,request,actor,RequestMetadataFactory.from(http));
    }
}
