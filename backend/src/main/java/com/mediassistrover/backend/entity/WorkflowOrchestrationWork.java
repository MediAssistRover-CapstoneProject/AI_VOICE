package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="WorkflowOrchestrationWork")
@Getter @Setter @NoArgsConstructor
public class WorkflowOrchestrationWork {
    @Id @Column(name="job_id",columnDefinition="BINARY(16)") private UUID jobId;
    @Column(name="requested_generation",nullable=false) private long requestedGeneration;
    @Column(name="processed_generation",nullable=false) private long processedGeneration;
    @Column(name="requested_at",nullable=false) private Instant requestedAt;
}
