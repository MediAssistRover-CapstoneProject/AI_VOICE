package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "VoiceInteraction")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInteraction {
    @Id
    @Column(name = "voice_interaction_id", columnDefinition = "BINARY(16)")
    private UUID voiceInteractionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    @Column(name = "language_code", nullable = false, length = 16)
    private String languageCode;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "visibility_scope", nullable = false, length = 16)
    private String visibilityScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VoiceInteractionStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "detected_intent", length = 80)
    private String detectedIntent;

    @Column(name = "transcript_summary", columnDefinition = "TEXT")
    private String transcriptSummary;

    @Column(name = "audio_uri", columnDefinition = "TEXT")
    private String audioUri;
}
