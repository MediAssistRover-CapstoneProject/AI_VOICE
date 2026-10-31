package com.mediassistrover.backend.controller.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.dto.job.request.MappingRecoveryRequest;
import com.mediassistrover.backend.mapping.MappingRecoveryService;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/mapping-recovery")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Tag(name = "Mapping session")
public class MappingRecoveryController {
  private final MappingRecoveryService service;

  @GetMapping
  @Operation(
      summary =
          "Inspect recovery eligibility for a selected mapping cleanup STOP that was never sent")
  public JsonNode evidence(
      @PathVariable UUID jobId, @AuthenticationPrincipal AccountPrincipal actor) {
    return service.evidence(jobId, actor);
  }

  @PostMapping
  @Operation(
      summary =
          "Close an unsent mapping cleanup STOP after verified Pi stop receipts and a fresh"
              + " closed-session heartbeat; never executes or replays STOP")
  public JsonNode reconcile(
      @PathVariable UUID jobId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody MappingRecoveryRequest request,
      @AuthenticationPrincipal AccountPrincipal actor,
      HttpServletRequest http) {
    return service.reconcile(jobId, key, request, actor, RequestMetadataFactory.from(http));
  }
}
