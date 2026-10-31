package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "HospitalInformationArticle", uniqueConstraints = @UniqueConstraint(
        name = "uq_hospital_article_code_language",
        columnNames = {"hospital_information_id", "article_code", "language_code"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HospitalInformationArticle {
    @Id @GeneratedValue @UuidGenerator
    @Column(name = "article_id", columnDefinition = "BINARY(16)")
    private UUID articleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_information_id", nullable = false)
    private HospitalInformation hospitalInformation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by_account_id", nullable = false)
    private Account updatedBy;

    @Column(name = "article_code", nullable = false, length = 80) private String articleCode;
    @Column(name = "category", nullable = false, length = 64) private String category;
    @Column(name = "canonical_question", nullable = false, length = 500) private String canonicalQuestion;
    @Column(name = "title", nullable = false, length = 255) private String title;
    @Column(name = "answer", nullable = false, columnDefinition = "TEXT") private String answer;
    @Column(name = "language_code", nullable = false, length = 16) private String languageCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords_json", nullable = false, columnDefinition = "JSON")
    private JsonNode keywordsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HospitalInformationStatus status;
    @Column(name = "is_public", nullable = false) private boolean publicVisible;
    @Column(name = "effective_from") private Instant effectiveFrom;
    @Column(name = "effective_until") private Instant effectiveUntil;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
