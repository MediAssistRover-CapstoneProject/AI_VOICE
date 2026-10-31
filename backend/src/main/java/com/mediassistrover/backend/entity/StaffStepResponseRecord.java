package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="JobStepResponse") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class StaffStepResponseRecord {
    @Id @GeneratedValue @UuidGenerator @Column(name="response_id",columnDefinition="BINARY(16)") private UUID responseId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_step_id",nullable=false) private JobStep jobStep;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="actor_account_id",nullable=false) private Account actorAccount;
    @Column(name="idempotency_key",nullable=false,length=150) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="request_json",nullable=false,columnDefinition="JSON") private JsonNode requestJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="result_json",nullable=false,columnDefinition="JSON") private JsonNode resultJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
