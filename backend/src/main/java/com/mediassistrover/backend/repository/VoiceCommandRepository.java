package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.VoiceCommand;
import com.mediassistrover.backend.enums.VoiceCommandStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VoiceCommandRepository extends JpaRepository<VoiceCommand, UUID> {
    boolean existsByVoiceInteractionVoiceInteractionIdAndSequenceNo(UUID interactionId, int sequenceNo);
    boolean existsByVoiceInteractionVoiceInteractionIdAndStatus(UUID interactionId, VoiceCommandStatus status);
    List<VoiceCommand> findByVoiceInteractionVoiceInteractionIdOrderBySequenceNoAsc(UUID interactionId);

    @Query("select c from VoiceCommand c where (:interactionId is null or c.voiceInteraction.voiceInteractionId=:interactionId) " +
            "and (:status is null or c.status=:status) and (:from is null or c.receivedAt>=:from) " +
            "and (:to is null or c.receivedAt<:to)")
    Page<VoiceCommand> search(@Param("interactionId") UUID interactionId,
                              @Param("status") VoiceCommandStatus status,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              Pageable pageable);
}
