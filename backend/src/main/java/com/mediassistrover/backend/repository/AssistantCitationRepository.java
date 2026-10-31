package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.AssistantCitation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AssistantCitationRepository extends JpaRepository<AssistantCitation, UUID> {
    List<AssistantCitation> findByTurnTurnIdOrderByOrdinalNoAsc(UUID turnId);
}
