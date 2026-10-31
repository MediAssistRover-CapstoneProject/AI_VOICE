package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.pi.response.PiResponses.AckResponse;
import com.mediassistrover.backend.dto.voice.request.VoiceRequests.*;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ConflictException;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.mapper.VoiceMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.VoiceService;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoiceServiceImpl implements VoiceService {
    private static final Set<VoiceInteractionStatus> TERMINAL_INTERACTION_STATUSES = Set.of(
            VoiceInteractionStatus.COMPLETED, VoiceInteractionStatus.CANCELLED, VoiceInteractionStatus.FAILED);
    private static final Set<VoiceCommandStatus> TERMINAL_COMMAND_STATUSES = Set.of(
            VoiceCommandStatus.REJECTED, VoiceCommandStatus.EXECUTED, VoiceCommandStatus.FAILED);

    private final VoiceInteractionRepository interactionRepository;
    private final VoiceCommandRepository commandRepository;
    private final RobotRepository robotRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final VoiceMapper mapper;
    private final StaffOperationalScopePolicy staffScope;
    private final Clock clock;

    @Override
    @Transactional
    public AckResponse startInteraction(PiEnvelope<InteractionStartPayload> envelope, DevicePrincipal principal) {
        validateEnvelope(envelope, principal, "VOICE_INTERACTION_STARTED");
        Robot robot = robot(principal.robotId());
        VoiceInteraction existing = interactionRepository.findById(envelope.messageId()).orElse(null);
        if (existing != null) {
            ensureRobot(existing, robot);
            return ack(envelope, robot, true);
        }
        InteractionStartPayload payload = envelope.payload();
        validateNotFarFuture(payload.startedAt(), "startedAt");
        interactionRepository.save(VoiceInteraction.builder()
                .voiceInteractionId(envelope.messageId())
                .robot(robot)
                .languageCode(payload.languageCode().replace('_', '-'))
                .channel("LEGACY")
                .visibilityScope("PUBLIC")
                .status(VoiceInteractionStatus.ACTIVE)
                .startedAt(payload.startedAt())
                .lastActivityAt(payload.startedAt())
                .build());
        return ack(envelope, robot, false);
    }

    @Override
    @Transactional
    public AckResponse completeInteraction(UUID interactionId,
                                           PiEnvelope<InteractionCompletionPayload> envelope,
                                           DevicePrincipal principal) {
        validateEnvelope(envelope, principal, "VOICE_INTERACTION_COMPLETED");
        Robot robot = robot(principal.robotId());
        VoiceInteraction interaction = interaction(interactionId);
        ensureRobot(interaction, robot);
        InteractionCompletionPayload payload = envelope.payload();
        if (!TERMINAL_INTERACTION_STATUSES.contains(payload.status())) {
            throw new BusinessRuleException("Interaction completion status must be COMPLETED, CANCELLED or FAILED");
        }
        if (interaction.getStatus() != VoiceInteractionStatus.ACTIVE) {
            if (interaction.getStatus() == payload.status()) {
                return ack(envelope, robot, true);
            }
            throw new ConflictException("Voice interaction is already terminal with status " + interaction.getStatus());
        }
        if (payload.endedAt().isBefore(interaction.getStartedAt())) {
            throw new BusinessRuleException("endedAt must not be before startedAt");
        }
        validateNotFarFuture(payload.endedAt(), "endedAt");
        validateReference(payload.audioUri(), "audioUri");
        if (payload.status() == VoiceInteractionStatus.COMPLETED &&
                commandRepository.existsByVoiceInteractionVoiceInteractionIdAndStatus(
                        interactionId, VoiceCommandStatus.RECEIVED)) {
            throw new BusinessRuleException("Cannot complete an interaction while a command is still RECEIVED");
        }
        interaction.setStatus(payload.status());
        interaction.setEndedAt(payload.endedAt());
        interaction.setDetectedIntent(trimToNull(payload.detectedIntent()));
        interaction.setTranscriptSummary(trimToNull(payload.transcriptSummary()));
        interaction.setAudioUri(trimToNull(payload.audioUri()));
        return ack(envelope, robot, false);
    }

    @Override
    @Transactional
    public AckResponse recordCommand(PiEnvelope<CommandPayload> envelope, DevicePrincipal principal) {
        validateEnvelope(envelope, principal, "VOICE_COMMAND");
        Robot robot = robot(principal.robotId());
        VoiceCommand existing = commandRepository.findById(envelope.messageId()).orElse(null);
        if (existing != null) {
            ensureRobot(existing.getVoiceInteraction(), robot);
            return ack(envelope, robot, true);
        }
        CommandPayload payload = envelope.payload();
        VoiceInteraction interaction = interaction(payload.voiceInteractionId());
        ensureRobot(interaction, robot);
        if (interaction.getStatus() != VoiceInteractionStatus.ACTIVE) {
            throw new BusinessRuleException("Commands can only be added to an ACTIVE interaction");
        }
        if (payload.receivedAt().isBefore(interaction.getStartedAt())) {
            throw new BusinessRuleException("receivedAt must not be before interaction startedAt");
        }
        validateNotFarFuture(payload.receivedAt(), "receivedAt");
        validateParameters(payload.parameters());
        validateCommandTiming(payload.status(), payload.receivedAt(), payload.processedAt());
        if (commandRepository.existsByVoiceInteractionVoiceInteractionIdAndSequenceNo(
                interaction.getVoiceInteractionId(), payload.sequenceNo())) {
            throw new ConflictException("sequenceNo already exists in this voice interaction");
        }
        ServiceRequest serviceRequest = serviceRequest(payload.serviceRequestId(), robot);
        commandRepository.save(VoiceCommand.builder()
                .voiceCommandId(envelope.messageId())
                .voiceInteraction(interaction)
                .serviceRequest(serviceRequest)
                .sequenceNo(payload.sequenceNo())
                .rawText(payload.rawText().trim())
                .normalizedText(trimToNull(payload.normalizedText()))
                .intentCode(trimToNull(payload.intentCode()))
                .confidenceScore(payload.confidenceScore())
                .commandType(payload.commandType())
                .parametersJson(payload.parameters())
                .status(payload.status())
                .receivedAt(payload.receivedAt())
                .processedAt(payload.processedAt())
                .build());
        return ack(envelope, robot, false);
    }

    @Override
    @Transactional
    public AckResponse recordCommandResult(UUID commandId,
                                           PiEnvelope<CommandResultPayload> envelope,
                                           DevicePrincipal principal) {
        validateEnvelope(envelope, principal, "VOICE_COMMAND_RESULT");
        Robot robot = robot(principal.robotId());
        VoiceCommand command = command(commandId);
        ensureRobot(command.getVoiceInteraction(), robot);
        CommandResultPayload payload = envelope.payload();
        if (payload.status() == VoiceCommandStatus.RECEIVED) {
            throw new BusinessRuleException("Command result status cannot be RECEIVED");
        }
        ServiceRequest requestedLink = serviceRequest(payload.serviceRequestId(), robot);
        UUID currentRequestId = command.getServiceRequest() == null ? null : command.getServiceRequest().getServiceRequestId();
        UUID requestedRequestId = requestedLink == null ? currentRequestId : requestedLink.getServiceRequestId();
        if (command.getStatus() == payload.status() && Objects.equals(currentRequestId, requestedRequestId)) {
            return ack(envelope, robot, true);
        }
        if (TERMINAL_COMMAND_STATUSES.contains(command.getStatus())) {
            throw new ConflictException("Voice command is already terminal with status " + command.getStatus());
        }
        boolean allowed = command.getStatus() == VoiceCommandStatus.RECEIVED ||
                command.getStatus() == VoiceCommandStatus.RESOLVED && TERMINAL_COMMAND_STATUSES.contains(payload.status());
        if (!allowed) {
            throw new BusinessRuleException("Invalid voice command transition: " + command.getStatus() + " -> " + payload.status());
        }
        if (payload.processedAt().isBefore(command.getReceivedAt())) {
            throw new BusinessRuleException("processedAt must not be before receivedAt");
        }
        validateNotFarFuture(payload.processedAt(), "processedAt");
        command.setStatus(payload.status());
        command.setProcessedAt(payload.processedAt());
        if (requestedLink != null) {
            command.setServiceRequest(requestedLink);
        }
        return ack(envelope, robot, false);
    }

    @Override
    public InteractionResponse getInteraction(UUID id, AccountPrincipal principal) {
        staffScope.requireCurrentStaffOrAdmin(principal);
        VoiceInteraction entity = interaction(id);
        InteractionResponse response = mapper.toResponse(entity);
        if (principal.role() == Role.ADMIN || interactionRawDataAvailable(entity, principal)) {
            return response;
        }
        return new InteractionResponse(response.voiceInteractionId(), null, response.robotId(),
                response.languageCode(), response.status(), response.startedAt(), response.endedAt(),
                response.detectedIntent(), null, null);
    }

    @Override
    public Page<InteractionMetadataResponse> listInteractions(UUID robotId, VoiceInteractionStatus status,
                                                               Instant from, Instant to,
                                                               AccountPrincipal principal, Pageable pageable) {
        staffScope.requireCurrentStaffOrAdmin(principal);
        validateWindow(from, to);
        return interactionRepository.search(robotId, status, from, to, pageable)
                .map(entity -> interactionMetadata(entity, principal));
    }

    @Override
    public CommandResponse getCommand(UUID id, AccountPrincipal principal) {
        staffScope.requireCurrentStaffOrAdmin(principal);
        VoiceCommand entity = command(id);
        if (principal.role() != Role.ADMIN) {
            if (entity.getServiceRequest() == null) {
                throw new AccessDeniedException("Voice command is not linked to an operational request");
            }
            staffScope.requireServiceRequestAccess(entity.getServiceRequest(), principal);
        }
        return mapper.toResponse(entity);
    }

    @Override
    public Page<CommandMetadataResponse> listCommands(UUID interactionId, VoiceCommandStatus status,
                                                       Instant from, Instant to,
                                                       AccountPrincipal principal, Pageable pageable) {
        staffScope.requireCurrentStaffOrAdmin(principal);
        validateWindow(from, to);
        return commandRepository.search(interactionId, status, from, to, pageable)
                .map(entity -> commandMetadata(entity, principal));
    }

    private InteractionMetadataResponse interactionMetadata(VoiceInteraction entity, AccountPrincipal principal) {
        boolean rawDataAvailable = principal.role() == Role.ADMIN || interactionRawDataAvailable(entity, principal);
        return new InteractionMetadataResponse(entity.getVoiceInteractionId(), entity.getRobot().getRobotId(),
                entity.getLanguageCode(), entity.getStatus(), entity.getStartedAt(), entity.getEndedAt(),
                entity.getDetectedIntent(), rawDataAvailable);
    }

    private CommandMetadataResponse commandMetadata(VoiceCommand entity, AccountPrincipal principal) {
        boolean rawDataAvailable = principal.role() == Role.ADMIN || commandRawDataAvailable(entity, principal);
        UUID requestId = rawDataAvailable && entity.getServiceRequest() != null
                ? entity.getServiceRequest().getServiceRequestId() : null;
        return new CommandMetadataResponse(entity.getVoiceCommandId(),
                entity.getVoiceInteraction().getVoiceInteractionId(), requestId, entity.getSequenceNo(),
                entity.getIntentCode(), entity.getConfidenceScore(), entity.getCommandType(), entity.getStatus(),
                entity.getReceivedAt(), entity.getProcessedAt(), rawDataAvailable);
    }

    private boolean interactionRawDataAvailable(VoiceInteraction entity, AccountPrincipal principal) {
        return commandRepository.findByVoiceInteractionVoiceInteractionIdOrderBySequenceNoAsc(
                        entity.getVoiceInteractionId()).stream()
                .anyMatch(command -> commandRawDataAvailable(command, principal));
    }

    private boolean commandRawDataAvailable(VoiceCommand entity, AccountPrincipal principal) {
        if (entity.getServiceRequest() == null) return false;
        try {
            staffScope.requireServiceRequestAccess(entity.getServiceRequest(), principal);
            return true;
        } catch (AccessDeniedException ignored) {
            return false;
        }
    }

    private void validateEnvelope(PiEnvelope<?> envelope, DevicePrincipal principal, String expectedType) {
        if (!"1.0".equals(envelope.protocolVersion())) {
            throw new BusinessRuleException("Unsupported protocolVersion");
        }
        if (!principal.robotId().equals(envelope.robotId())) {
            throw new BusinessRuleException("Envelope robotId does not match device token");
        }
        if (!expectedType.equals(envelope.messageType())) {
            throw new BusinessRuleException("Unexpected messageType; expected " + expectedType);
        }
        validateNotFarFuture(envelope.occurredAt(), "occurredAt");
    }

    private void validateCommandTiming(VoiceCommandStatus status, Instant receivedAt, Instant processedAt) {
        if (status == VoiceCommandStatus.RECEIVED && processedAt != null) {
            throw new BusinessRuleException("processedAt must be null while command status is RECEIVED");
        }
        if (status != VoiceCommandStatus.RECEIVED && processedAt == null) {
            throw new BusinessRuleException("processedAt is required after command processing");
        }
        if (processedAt != null && processedAt.isBefore(receivedAt)) {
            throw new BusinessRuleException("processedAt must not be before receivedAt");
        }
        if (processedAt != null) {
            validateNotFarFuture(processedAt, "processedAt");
        }
    }

    private void validateParameters(com.fasterxml.jackson.databind.JsonNode parameters) {
        if (!parameters.isObject()) {
            throw new BusinessRuleException("parameters must be a JSON object");
        }
    }

    private void validateReference(String value, String field) {
        if (value != null && value.regionMatches(true, 0, "data:", 0, 5)) {
            throw new BusinessRuleException(field + " must be a reference, not embedded data");
        }
    }

    private void validateNotFarFuture(Instant value, String field) {
        if (value.isAfter(Instant.now(clock).plusSeconds(300))) {
            throw new BusinessRuleException(field + " is too far in the future");
        }
    }

    private void validateWindow(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessRuleException("from must be before to");
        }
    }

    private Robot robot(UUID id) {
        // Serializes device messages per robot so concurrent retries cannot race the idempotency checks.
        return robotRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Robot not found: " + id));
    }

    private VoiceInteraction interaction(UUID id) {
        return interactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("VoiceInteraction not found: " + id));
    }

    private VoiceCommand command(UUID id) {
        return commandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("VoiceCommand not found: " + id));
    }

    private ServiceRequest serviceRequest(UUID id, Robot robot) {
        if (id == null) {
            return null;
        }
        ServiceRequest request = serviceRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServiceRequest not found: " + id));
        if (request.getRequestedRobot() == null ||
                !request.getRequestedRobot().getRobotId().equals(robot.getRobotId())) {
            throw new BusinessRuleException("ServiceRequest is not owned by this robot");
        }
        return request;
    }

    private void ensureRobot(VoiceInteraction interaction, Robot robot) {
        if (!interaction.getRobot().getRobotId().equals(robot.getRobotId())) {
            throw new BusinessRuleException("Voice interaction belongs to another robot");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AckResponse ack(PiEnvelope<?> envelope, Robot robot, boolean duplicate) {
        return new AckResponse(envelope.messageId(), envelope.operationKey(), envelope.correlationId(),
                true, duplicate, Instant.now(clock), robot.getStatus(), false, false, null);
    }
}
