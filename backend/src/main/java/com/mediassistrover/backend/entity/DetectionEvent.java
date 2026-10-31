package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="DetectionEvent")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DetectionEvent {
    @Id @Column(name="detection_event_id",columnDefinition="BINARY(16)") private UUID detectionEventId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="robot_id",nullable=false) private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="zone_id") private Zone zone;
    @Enumerated(EnumType.STRING) @Column(name="event_type",nullable=false,length=40) private DetectionEventType eventType;
    @Column(name="confidence_score",precision=5,scale=4) private BigDecimal confidenceScore;
    @Column(name="x_m",precision=12,scale=4) private BigDecimal xM;
    @Column(name="y_m",precision=12,scale=4) private BigDecimal yM;
    @Enumerated(EnumType.STRING) @Column(name="location_basis",nullable=false,length=16) private DetectionLocationBasis locationBasis;
    @Column(name="model_version",length=100) private String modelVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="event_payload_json",nullable=false,columnDefinition="JSON") private JsonNode eventPayloadJson;
    @Column(name="evidence_ref",columnDefinition="TEXT") private String evidenceRef;
    @Column(name="detected_at",nullable=false) private Instant detectedAt;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
}
