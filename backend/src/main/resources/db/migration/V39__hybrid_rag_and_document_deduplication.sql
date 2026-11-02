ALTER TABLE `KnowledgeDocument`
  ADD COLUMN duplicate_of_document_id BINARY(16) NULL AFTER replaces_document_id,
  ADD CONSTRAINT fk_knowledge_document_duplicate FOREIGN KEY (duplicate_of_document_id) REFERENCES `KnowledgeDocument` (document_id),
  ADD INDEX ix_knowledge_document_duplicate (duplicate_of_document_id);

ALTER TABLE `KnowledgeDocumentVersion`
  ADD INDEX ix_knowledge_version_sha_ready (sha256, status, ready_at);

ALTER TABLE `KnowledgeChunk`
  ADD COLUMN chunk_type VARCHAR(16) NOT NULL DEFAULT 'TEXT' AFTER chunk_index,
  ADD COLUMN content_hash CHAR(64) NULL AFTER content,
  ADD COLUMN locator VARCHAR(255) NULL AFTER content_hash;

UPDATE `KnowledgeChunk` SET content_hash=SHA2(LOWER(TRIM(content)),256) WHERE content_hash IS NULL;

ALTER TABLE `KnowledgeChunk`
  MODIFY COLUMN content_hash CHAR(64) NOT NULL,
  ADD CONSTRAINT chk_knowledge_chunk_type CHECK (chunk_type IN ('TEXT','TABLE')),
  ADD INDEX ix_knowledge_chunk_hash (content_hash),
  ADD INDEX ix_knowledge_chunk_type (chunk_type);
