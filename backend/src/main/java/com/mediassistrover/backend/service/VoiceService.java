package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.response.PiResponses.AckResponse;
import com.mediassistrover.backend.dto.voice.request.VoiceRequests.*;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.*;
import com.mediassistrover.backend.enums.VoiceCommandStatus;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.security.AccountPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface VoiceService {
    AckResponse startInteraction(PiEnvelope<InteractionStartPayload> envelope, DevicePrincipal principal);
    AckResponse completeInteraction(UUID interactionId, PiEnvelope<InteractionCompletionPayload> envelope, DevicePrincipal principal);
    AckResponse recordCommand(PiEnvelope<CommandPayload> envelope, DevicePrincipal principal);
    AckResponse recordCommandResult(UUID commandId, PiEnvelope<CommandResultPayload> envelope, DevicePrincipal principal);
    InteractionResponse getInteraction(UUID id, AccountPrincipal principal);
    Page<InteractionMetadataResponse> listInteractions(UUID robotId, VoiceInteractionStatus status, Instant from,
                                                        Instant to, AccountPrincipal principal, Pageable pageable);
    CommandResponse getCommand(UUID id, AccountPrincipal principal);
    Page<CommandMetadataResponse> listCommands(UUID interactionId, VoiceCommandStatus status, Instant from,
                                                Instant to, AccountPrincipal principal, Pageable pageable);
}
