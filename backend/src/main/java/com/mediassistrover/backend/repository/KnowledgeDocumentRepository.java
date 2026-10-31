package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> { }
