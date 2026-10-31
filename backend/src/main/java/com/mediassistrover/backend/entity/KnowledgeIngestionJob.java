package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="KnowledgeIngestionJob")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class KnowledgeIngestionJob {
    @Id @GeneratedValue @UuidGenerator @Column(name="ingestion_job_id",columnDefinition="BINARY(16)") private UUID ingestionJobId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="version_id",nullable=false) private KnowledgeDocumentVersion version;
    @Column(name="status",nullable=false,length=24) private String status;
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    @Column(name="next_attempt_at",nullable=false) private Instant nextAttemptAt;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="ended_at") private Instant endedAt;
    @Column(name="error_code",length=80) private String errorCode;
    @Column(name="error_message",length=1000) private String errorMessage;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
