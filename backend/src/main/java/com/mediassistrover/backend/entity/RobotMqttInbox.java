package com.mediassistrover.backend.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="RobotMqttInbox") @Getter @Setter @NoArgsConstructor
public class RobotMqttInbox {
    @Id @Column(name="message_id",columnDefinition="BINARY(16)") private UUID messageId;
    @Column(name="robot_id",nullable=false,columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="producer_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID producerSessionId;
    @Column(name="sequence_no",nullable=false) private long sequenceNo;
    @Column(name="transport_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID transportSessionId;
    @Column(name="message_type",nullable=false,length=32) private String messageType;
    @Column(name="canonical_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String canonicalHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="raw_envelope",nullable=false,columnDefinition="JSON") private JsonNode rawEnvelope;
    @Column(name="state",nullable=false,length=24) private String state;
    @Column(name="accepted") private Boolean accepted;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="response_json",columnDefinition="JSON") private JsonNode responseJson;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
    @Column(name="processed_at") private Instant processedAt;
    @Column(name="ack_published_at") private Instant ackPublishedAt;
    @Column(name="failure_reason",length=2000) private String failureReason;
}
