package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="KnowledgeDocumentVersion")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class KnowledgeDocumentVersion {
    @Id @GeneratedValue @UuidGenerator @Column(name="version_id",columnDefinition="BINARY(16)") private UUID versionId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="document_id",nullable=false) private KnowledgeDocument document;
    @Column(name="version_no",nullable=false) private int versionNo;
    @Column(name="original_filename",nullable=false,length=255) private String originalFilename;
    @Column(name="media_type",nullable=false,length=100) private String mediaType;
    @Column(name="storage_path",nullable=false,length=1000) private String storagePath;
    @Column(name="sha256",nullable=false,length=64,columnDefinition="CHAR(64)") private String sha256;
    @Column(name="size_bytes",nullable=false) private long sizeBytes;
    @Column(name="page_count") private Integer pageCount;
    @Column(name="extracted_text",columnDefinition="LONGTEXT") private String extractedText;
    @Column(name="extraction_method",length=32) private String extractionMethod;
    @Column(name="status",nullable=false,length=24) private String status;
    @Column(name="error_code",length=80) private String errorCode;
    @Column(name="error_message",length=1000) private String errorMessage;
    @Column(name="ready_at") private Instant readyAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
