package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeIngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface KnowledgeIngestionJobRepository extends JpaRepository<KnowledgeIngestionJob, UUID> {
    Optional<KnowledgeIngestionJob> findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status,Instant now);
}
