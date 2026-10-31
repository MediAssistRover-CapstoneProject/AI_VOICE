package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="AssistantCitation")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AssistantCitation {
    @Id @GeneratedValue @UuidGenerator @Column(name="citation_id",columnDefinition="BINARY(16)") private UUID citationId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="turn_id",nullable=false) private VoiceAssistantTurn turn;
    @Column(name="ordinal_no",nullable=false) private int ordinalNo;
    @Column(name="source_type",nullable=false,length=24) private String sourceType;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="document_id") private KnowledgeDocument document;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="article_id") private HospitalInformationArticle article;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="chunk_id") private KnowledgeChunk chunk;
    @Column(name="title",nullable=false,length=255) private String title;
    @Column(name="locator",length=255) private String locator;
    @Column(name="effective_at") private Instant effectiveAt;
    @Column(name="score",precision=8,scale=6) private BigDecimal score;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
