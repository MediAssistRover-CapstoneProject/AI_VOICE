package com.mediassistrover.backend.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="MonitoringIngestionReceipt") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MonitoringIngestionReceipt {
    @Id @Column(name="message_id",columnDefinition="BINARY(16)") private UUID messageId;
    @Column(name="robot_id",nullable=false,columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="message_type",nullable=false,length=40) private String messageType;
    @Column(name="payload_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String payloadHash;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) @Column(name="provenance_json",columnDefinition="JSON") private com.fasterxml.jackson.databind.JsonNode provenanceJson;
}
