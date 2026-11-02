package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> { }
