package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="KnowledgeDocument") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class KnowledgeDocument {
    @Id @GeneratedValue @UuidGenerator @Column(name="document_id",columnDefinition="BINARY(16)") private UUID documentId;
    @Column(name="title",nullable=false,length=255) private String title;
    @Column(name="category",nullable=false,length=80) private String category;
    @Column(name="language_code",nullable=false,length=16) private String languageCode;
    @Column(name="visibility",nullable=false,length=16) private String visibility;
    @Column(name="status",nullable=false,length=24) private String status;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="replaces_document_id") private KnowledgeDocument replacesDocument;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="current_version_id") private KnowledgeDocumentVersion currentVersion;
    @Column(name="effective_from") private Instant effectiveFrom;
    @Column(name="effective_until") private Instant effectiveUntil;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="created_by_account_id",nullable=false) private Account createdBy;
    @CreatedDate @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @LastModifiedDate @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
}
