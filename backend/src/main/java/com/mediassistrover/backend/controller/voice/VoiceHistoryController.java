package com.mediassistrover.backend.controller.voice;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.CommandResponse;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.CommandMetadataResponse;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.InteractionResponse;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.InteractionMetadataResponse;
import com.mediassistrover.backend.enums.VoiceCommandStatus;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import com.mediassistrover.backend.service.VoiceService;
import com.mediassistrover.backend.security.AccountPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Tag(name = "Voice History", description = "Read-only voice interaction and command history")
public class VoiceHistoryController {
    private final VoiceService service;

    @GetMapping("/interactions")
    public PageResponse<InteractionMetadataResponse> interactions(
            @RequestParam(required = false) UUID robotId,
            @RequestParam(required = false) VoiceInteractionStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return PageResponse.from(service.listInteractions(robotId, status, from, to, principal,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"))));
    }

    @GetMapping("/interactions/{id}")
    public InteractionResponse interaction(@PathVariable UUID id,
                                           @AuthenticationPrincipal AccountPrincipal principal) {
        return service.getInteraction(id, principal);
    }

    @GetMapping("/commands")
    public PageResponse<CommandMetadataResponse> commands(
            @RequestParam(required = false) UUID interactionId,
            @RequestParam(required = false) VoiceCommandStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return PageResponse.from(service.listCommands(interactionId, status, from, to, principal,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"))));
    }

    @GetMapping("/commands/{id}")
    public CommandResponse command(@PathVariable UUID id,
                                   @AuthenticationPrincipal AccountPrincipal principal) {
        return service.getCommand(id, principal);
    }
}
