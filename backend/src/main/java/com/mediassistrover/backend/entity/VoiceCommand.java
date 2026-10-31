package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.VoiceCommandStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "VoiceCommand", uniqueConstraints = @UniqueConstraint(
        name = "UQ_VoiceCommand_interaction_sequence",
        columnNames = {"voice_interaction_id", "sequence_no"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceCommand {
    @Id
    @Column(name = "voice_command_id", columnDefinition = "BINARY(16)")
    private UUID voiceCommandId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voice_interaction_id", nullable = false)
    private VoiceInteraction voiceInteraction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_request_id")
    private ServiceRequest serviceRequest;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "raw_text", nullable = false, columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "normalized_text", columnDefinition = "TEXT")
    private String normalizedText;

    @Column(name = "intent_code", length = 80)
    private String intentCode;

    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(name = "command_type", nullable = false, length = 64)
    private String commandType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", nullable = false, columnDefinition = "JSON")
    private JsonNode parametersJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VoiceCommandStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
