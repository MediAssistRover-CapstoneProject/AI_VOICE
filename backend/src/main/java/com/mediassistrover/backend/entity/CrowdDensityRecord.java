package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.CrowdDensityLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="CrowdDensityRecord")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CrowdDensityRecord {
    @Id @Column(name="crowd_density_record_id",columnDefinition="BINARY(16)") private UUID crowdDensityRecordId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="robot_id",nullable=false) private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="zone_id",nullable=false) private Zone zone;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="checkpoint_id") private Endpoint checkpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_step_id") private JobStep jobStep;
    @Column(name="person_count",nullable=false) private int personCount;
    @Enumerated(EnumType.STRING) @Column(name="density_level",length=16) private CrowdDensityLevel densityLevel;
    @Column(name="confidence_score",precision=5,scale=4) private BigDecimal confidenceScore;
    @Column(name="model_version",length=100) private String modelVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="metrics_json",nullable=false,columnDefinition="JSON") private JsonNode metricsJson;
    @Column(name="evidence_ref",columnDefinition="TEXT") private String evidenceRef;
    @Column(name="captured_at",nullable=false) private Instant capturedAt;
    @Column(name="recorded_at",nullable=false) private Instant recordedAt;
}
