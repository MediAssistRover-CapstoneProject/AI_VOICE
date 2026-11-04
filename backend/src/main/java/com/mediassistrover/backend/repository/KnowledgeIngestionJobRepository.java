package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeIngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface KnowledgeIngestionJobRepository extends JpaRepository<KnowledgeIngestionJob, UUID> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true)
    @org.springframework.data.jpa.repository.Query("update KnowledgeIngestionJob j set j.status='RUNNING' where j.ingestionJobId=:id and j.status='PENDING'")
    int claimPending(@org.springframework.data.repository.query.Param("id") UUID id);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update KnowledgeIngestionJob j set j.status='FAILED', j.errorCode='DOCUMENT_ARCHIVED' where j.version.document.documentId=:id and j.status='PENDING'")
    void cancelPending(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<KnowledgeIngestionJob> findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status,Instant now);
}
