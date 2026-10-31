package com.mediassistrover.backend.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="RobotCommandOutbox") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RobotCommandOutbox {
    @Id @Column(name="command_id",columnDefinition="BINARY(16)") private UUID commandId;
    @Column(name="robot_id",nullable=false,columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="job_id",nullable=false,columnDefinition="BINARY(16)") private UUID jobId;
    @Column(name="job_step_id",nullable=false,columnDefinition="BINARY(16)") private UUID jobStepId;
    @Column(name="operation_key",nullable=false,length=150) private String operationKey;
    @Builder.Default @Column(name="command_type",nullable=false,length=24) private String commandType="EXECUTE_STEP";
    @Column(name="interruption_reason",length=32) private String interruptionReason;
    @Column(name="physical_stop_confirmed") private Boolean physicalStopConfirmed;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="result_envelope_json",columnDefinition="JSON") private JsonNode resultEnvelopeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="payload_json",nullable=false,columnDefinition="JSON") private JsonNode payloadJson;
    @Column(name="expires_at",nullable=false) private Instant expiresAt;
    @Column(name="state",nullable=false,length=32) private String state;
    @Column(name="send_attempts",nullable=false) private int sendAttempts;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="last_publish_at") private Instant lastPublishAt;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;
    @Column(name="navigation_profile_hash",length=64,columnDefinition="CHAR(64)") private String navigationProfileHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="ai_profile_snapshot_json",columnDefinition="JSON") private JsonNode aiProfileSnapshotJson;
}
