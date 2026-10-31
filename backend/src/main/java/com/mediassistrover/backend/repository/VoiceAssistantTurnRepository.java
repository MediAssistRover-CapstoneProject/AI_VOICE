package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.VoiceAssistantTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
public interface VoiceAssistantTurnRepository extends JpaRepository<VoiceAssistantTurn, UUID> {
    Optional<VoiceAssistantTurn> findByInteractionVoiceInteractionIdAndIdempotencyKey(UUID interactionId,String idempotencyKey);
    List<VoiceAssistantTurn> findTop10ByInteractionVoiceInteractionIdOrderBySequenceNoDesc(UUID interactionId);
    Optional<VoiceAssistantTurn> findFirstByInteractionVoiceInteractionIdAndServiceRequestIsNotNullOrderBySequenceNoDesc(UUID interactionId);
    long countByInteractionVoiceInteractionId(UUID interactionId);
    List<VoiceAssistantTurn> findByCreatedAtBefore(Instant cutoff);
    long deleteByCreatedAtBefore(Instant cutoff);
}
