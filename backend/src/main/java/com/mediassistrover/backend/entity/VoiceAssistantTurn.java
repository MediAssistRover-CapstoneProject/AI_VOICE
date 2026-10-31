package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="VoiceAssistantTurn")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class VoiceAssistantTurn {
    @Id @GeneratedValue @UuidGenerator @Column(name="turn_id",columnDefinition="BINARY(16)") private UUID turnId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="voice_interaction_id",nullable=false) private VoiceInteraction interaction;
    @Column(name="sequence_no",nullable=false) private int sequenceNo;
    @Column(name="idempotency_key",nullable=false,length=150) private String idempotencyKey;
    @Column(name="status",nullable=false,length=24) private String status;
    @Column(name="transcript",columnDefinition="TEXT") private String transcript;
    @Column(name="detected_language",length=16) private String detectedLanguage;
    @Column(name="intent",length=24) private String intent;
    @Column(name="answer_text",columnDefinition="TEXT") private String answerText;
    @Column(name="speech_text",length=2000) private String speechText;
    @Column(name="grounding_mode",length=24) private String groundingMode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="warnings_json",columnDefinition="JSON") private JsonNode warningsJson;
    @Column(name="model_name",length=80) private String modelName;
    @Column(name="latency_ms") private Long latencyMs;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="action_json",columnDefinition="JSON") private JsonNode actionJson;
    @Column(name="confirmation_nonce_hash",length=64,columnDefinition="CHAR(64)") private String confirmationNonceHash;
    @Column(name="confirmation_expires_at") private Instant confirmationExpiresAt;
    @Column(name="confirmed_at") private Instant confirmedAt;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="service_request_id") private ServiceRequest serviceRequest;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id") private Job job;
    @Column(name="audio_path",length=1000) private String audioPath;
    @Column(name="audio_sha256",length=64,columnDefinition="CHAR(64)") private String audioSha256;
    @Column(name="error_code",length=80) private String errorCode;
    @Column(name="error_message",length=1000) private String errorMessage;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="completed_at") private Instant completedAt;
}
