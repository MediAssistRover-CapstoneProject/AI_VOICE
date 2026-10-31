package com.mediassistrover.backend.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="MappingSession") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MappingSession {
    @Id @Column(name="session_id",columnDefinition="BINARY(16)") private UUID sessionId;
    @Column(name="job_id",nullable=false,columnDefinition="BINARY(16)",unique=true) private UUID jobId;
    @Column(name="robot_id",nullable=false,columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="artifact_id",nullable=false,columnDefinition="BINARY(16)",unique=true) private UUID artifactId;
    @Column(name="state",nullable=false,length=32) private String state;
    @Column(name="potentially_open",nullable=false) private boolean potentiallyOpen;
    @Column(name="cleanup_terminal",length=24) private String cleanupTerminal;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="closed_at") private Instant closedAt;
}
