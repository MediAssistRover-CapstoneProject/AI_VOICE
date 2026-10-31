package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {
    List<KnowledgeChunk> findByVersionVersionIdOrderByChunkIndexAsc(UUID versionId);
    void deleteByVersionVersionId(UUID versionId);
    @Query(value="""
        SELECT c.* FROM KnowledgeChunk c
        JOIN KnowledgeDocumentVersion v ON v.version_id=c.version_id
        JOIN KnowledgeDocument d ON d.current_version_id=v.version_id
        WHERE d.status='READY' AND (:internal=TRUE OR d.visibility='PUBLIC')
          AND (d.effective_from IS NULL OR d.effective_from<=CURRENT_TIMESTAMP(6))
          AND (d.effective_until IS NULL OR d.effective_until>CURRENT_TIMESTAMP(6))
          AND MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE)
        ORDER BY MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE) DESC LIMIT 12
        """,nativeQuery=true)
    List<KnowledgeChunk> keywordSearch(@Param("query") String query,@Param("internal") boolean internal);
}
