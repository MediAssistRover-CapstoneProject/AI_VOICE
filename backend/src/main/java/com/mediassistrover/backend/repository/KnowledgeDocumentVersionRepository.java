package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface KnowledgeDocumentVersionRepository extends JpaRepository<KnowledgeDocumentVersion, UUID> {
    List<KnowledgeDocumentVersion> findByDocumentDocumentIdOrderByVersionNoDesc(UUID documentId);
}
