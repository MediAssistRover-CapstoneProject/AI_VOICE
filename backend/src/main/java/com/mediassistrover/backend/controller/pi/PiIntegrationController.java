package com.mediassistrover.backend.controller.pi;

import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.*;
import com.mediassistrover.backend.dto.pi.response.PiResponses.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.PiIntegrationService;
import com.mediassistrover.backend.service.PiJobRuntimeService;
import com.mediassistrover.backend.service.PiStepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.mediassistrover.backend.config.OpenApiConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/pi") @RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')") @Tag(name="Server–Pi Protocol v1")
@SecurityRequirement(name=OpenApiConfig.DEVICE_BEARER_SCHEME)
public class PiIntegrationController {
    private final PiIntegrationService service;
    private final PiJobRuntimeService jobRuntimeService;
    private final PiStepService piStepService;
    @PostMapping("/heartbeat") public AckResponse heartbeat(@Valid @RequestBody PiEnvelope<HeartbeatPayload> e,@AuthenticationPrincipal DevicePrincipal p){return service.heartbeat(e,p);}
    @PostMapping("/telemetry/location") public AckResponse location(@Valid @RequestBody PiEnvelope<LocationPayload> e,@AuthenticationPrincipal DevicePrincipal p){return service.location(e,p);}
    @GetMapping("/catalog/manifest") public CatalogManifestResponse manifest(@AuthenticationPrincipal DevicePrincipal p){return service.catalogManifest(p);}
    @GetMapping("/maps/{mapId}/metadata") public PiMapMetadata metadata(@PathVariable UUID mapId,@AuthenticationPrincipal DevicePrincipal p){return service.mapMetadata(mapId,p);}
    @GetMapping("/maps/{mapId}/download") public ResponseEntity<Void> download(@PathVariable UUID mapId,@AuthenticationPrincipal DevicePrincipal p){PiMapMetadata m=service.mapMetadata(mapId,p);return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(URI.create(m.mapFileUri())).build();}
    @GetMapping("/commands/next") @Operation(summary="Poll command kế tiếp",description="Trả DISPATCH/CANCEL; 204 khi không có command") public ResponseEntity<JobCommandResponse> nextCommand(@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.nextCommand(p).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());}
    @PostMapping("/commands/{commandId}/ack") public AckResponse commandAck(@PathVariable UUID commandId,@Valid @RequestBody PiEnvelope<CommandAckPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.commandAck(commandId,e,p);}
    @PostMapping("/jobs/{jobId}/started") public AckResponse jobStarted(@PathVariable UUID jobId,@Valid @RequestBody PiEnvelope<JobStartedPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.jobStarted(jobId,e,p);}
    @PostMapping("/jobs/{jobId}/result") public AckResponse jobResult(@PathVariable UUID jobId,@Valid @RequestBody PiEnvelope<JobResultPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.jobResult(jobId,e,p);}
    @PostMapping("/jobs/{jobId}/errors") public AckResponse jobError(@PathVariable UUID jobId,@Valid @RequestBody PiEnvelope<JobErrorPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.jobError(jobId,e,p);}
    @PostMapping("/job-steps/{jobStepId}/started") public AckResponse stepStarted(@PathVariable UUID jobStepId,@Valid @RequestBody PiEnvelope<StepStartedPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.stepStarted(jobStepId,e,p);}
    @PostMapping("/job-steps/{jobStepId}/result") public AckResponse stepResult(@PathVariable UUID jobStepId,@Valid @RequestBody PiEnvelope<StepResultPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.stepResult(jobStepId,e,p);}
    @PostMapping("/job-steps/{jobStepId}/errors") public AckResponse stepError(@PathVariable UUID jobStepId,@Valid @RequestBody PiEnvelope<StepErrorPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.stepError(jobStepId,e,p);}
    @PostMapping("/jobs/{jobId}/steps/{stepId}/visits") public JobStepCommand createStepVisit(@PathVariable UUID jobId,@PathVariable UUID stepId,@Valid @RequestBody PiEnvelope<CreateStepVisitPayload> e,@AuthenticationPrincipal DevicePrincipal p){return jobRuntimeService.createStepVisit(jobId,stepId,e,p);}
    @PostMapping("/step-services/execute") public StepServiceExecutionResponse executeServerStep(@Valid @RequestBody PiEnvelope<StepServiceExecutePayload> e,@AuthenticationPrincipal DevicePrincipal p){return piStepService.execute(e,p);}
    @GetMapping("/job-steps/{jobStepId}/service-result") public StepServiceExecutionResponse serverStepResult(@PathVariable UUID jobStepId,@AuthenticationPrincipal DevicePrincipal p){return piStepService.result(jobStepId,p);}
}
