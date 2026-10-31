package com.mediassistrover.backend.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="AiObservation") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AiObservation {
    @Id @Column(name="observation_key",length=64,columnDefinition="CHAR(64)") private String observationKey;
    @Column(name="robot_id",nullable=false,columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="job_step_id",nullable=false,columnDefinition="BINARY(16)") private UUID jobStepId;
    @Column(name="payload_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String payloadHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="output_json",nullable=false,columnDefinition="JSON") private JsonNode outputJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="profile_snapshot_json",nullable=false,columnDefinition="JSON") private JsonNode profileSnapshotJson;
    @Column(name="crowd_record_id",columnDefinition="BINARY(16)") private UUID crowdRecordId;
    @Column(name="detection_event_id",columnDefinition="BINARY(16)") private UUID detectionEventId;
    @Column(name="stored_at",nullable=false) private Instant storedAt;
}
