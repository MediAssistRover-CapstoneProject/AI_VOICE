package com.mediassistrover.backend.controller.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.JobStepResponseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/job-steps") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
public class JobStepResponseController {
    private final JobStepResponseService service;
    @PostMapping("/{id}/responses")
    public JobStepResponseService.Receipt respond(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body,@AuthenticationPrincipal AccountPrincipal actor) {
        return service.respond(id,key,body,actor);
    }
}
