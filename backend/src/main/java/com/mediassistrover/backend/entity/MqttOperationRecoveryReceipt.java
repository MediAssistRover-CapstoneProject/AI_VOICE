package com.mediassistrover.backend.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="MqttOperationRecoveryReceipt") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MqttOperationRecoveryReceipt {
    @Id @Column(name="receipt_id",columnDefinition="BINARY(16)") private UUID receiptId;
    @Column(name="job_step_id",nullable=false,columnDefinition="BINARY(16)") private UUID jobStepId;
    @Column(name="actor_account_id",nullable=false,columnDefinition="BINARY(16)") private UUID actorAccountId;
    @Column(name="idempotency_key",nullable=false,length=150) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="request_json",nullable=false,columnDefinition="JSON") private JsonNode requestJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="response_json",nullable=false,columnDefinition="JSON") private JsonNode responseJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
