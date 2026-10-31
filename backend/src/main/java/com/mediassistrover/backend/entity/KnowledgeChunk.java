package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="KnowledgeChunk")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class KnowledgeChunk {
    @Id @GeneratedValue @UuidGenerator @Column(name="chunk_id",columnDefinition="BINARY(16)") private UUID chunkId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="version_id",nullable=false) private KnowledgeDocumentVersion version;
    @Column(name="chunk_index",nullable=false) private int chunkIndex;
    @Column(name="heading",length=500) private String heading;
    @Column(name="page_from") private Integer pageFrom;
    @Column(name="page_to") private Integer pageTo;
    @Column(name="content",nullable=false,columnDefinition="TEXT") private String content;
    @Column(name="token_estimate",nullable=false) private int tokenEstimate;
    @Column(name="qdrant_point_id",nullable=false,length=36,columnDefinition="CHAR(36)") private String qdrantPointId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
