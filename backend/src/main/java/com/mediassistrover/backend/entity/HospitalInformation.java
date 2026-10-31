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

@Entity @Table(name = "HospitalInformation") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HospitalInformation {
    @Id @GeneratedValue @UuidGenerator
    @Column(name = "hospital_information_id", columnDefinition = "BINARY(16)")
    private UUID hospitalInformationId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by_account_id", nullable = false)
    private Account updatedBy;
    @Column(name = "info_category", nullable = false, length = 64) private String infoCategory;
    @Column(name = "title", nullable = false, length = 255) private String title;
    @Column(name = "content", nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "language_code", nullable = false, length = 16) private String languageCode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "keywords_json", nullable = false, columnDefinition = "JSON")
    private JsonNode keywordsJson;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 20)
    private HospitalInformationStatus status;
    @Column(name = "is_public", nullable = false) private boolean publicVisible;
    @Column(name = "effective_from") private Instant effectiveFrom;
    @Column(name = "effective_until") private Instant effectiveUntil;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
