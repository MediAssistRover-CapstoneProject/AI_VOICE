package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from KnowledgeDocument d where d.documentId=:id")
    Optional<KnowledgeDocument> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
