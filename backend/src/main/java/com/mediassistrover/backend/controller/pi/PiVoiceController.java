package com.mediassistrover.backend.controller.pi;

import com.mediassistrover.backend.config.OpenApiConfig;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.response.PiResponses.AckResponse;
import com.mediassistrover.backend.dto.voice.request.VoiceRequests.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.VoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pi/voice")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DEVICE')")
@SecurityRequirement(name = OpenApiConfig.DEVICE_BEARER_SCHEME)
@Tag(name = "Server-Pi Voice", description = "Voice event contract; Backend does not perform speech recognition or intent AI")
public class PiVoiceController {
    private final VoiceService service;

    @PostMapping("/interactions")
    @Operation(summary = "Start an idempotent voice interaction")
    public AckResponse start(@Valid @RequestBody PiEnvelope<InteractionStartPayload> envelope,
                             @AuthenticationPrincipal DevicePrincipal principal) {
        return service.startInteraction(envelope, principal);
    }

    @PostMapping("/interactions/{interactionId}/completion")
    @Operation(summary = "Complete, cancel or fail an active voice interaction")
    public AckResponse complete(@PathVariable UUID interactionId,
                                @Valid @RequestBody PiEnvelope<InteractionCompletionPayload> envelope,
                                @AuthenticationPrincipal DevicePrincipal principal) {
        return service.completeInteraction(interactionId, envelope, principal);
    }

    @PostMapping("/commands")
    @Operation(summary = "Append an idempotent recognized voice command")
    public AckResponse command(@Valid @RequestBody PiEnvelope<CommandPayload> envelope,
                               @AuthenticationPrincipal DevicePrincipal principal) {
        return service.recordCommand(envelope, principal);
    }

    @PostMapping("/commands/{commandId}/result")
    @Operation(summary = "Record the controlled processing result of a received voice command")
    public AckResponse commandResult(@PathVariable UUID commandId,
                                     @Valid @RequestBody PiEnvelope<CommandResultPayload> envelope,
                                     @AuthenticationPrincipal DevicePrincipal principal) {
        return service.recordCommandResult(commandId, envelope, principal);
    }
}
