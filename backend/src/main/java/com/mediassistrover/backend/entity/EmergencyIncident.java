package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="EmergencyIncident")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EmergencyIncident {
    @Id @GeneratedValue @UuidGenerator @Column(name="incident_id",columnDefinition="BINARY(16)") private UUID incidentId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="detection_event_id") private DetectionEvent detectionEvent;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="robot_id") private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="map_id") private FacilityMap map;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="endpoint_id") private Endpoint endpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="acknowledged_by_account_id") private Account acknowledgedByAccount;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="resolved_by_account_id") private Account resolvedByAccount;
    @Column(name="incident_type",nullable=false,length=64) private String incidentType;
    @Enumerated(EnumType.STRING) @Column(name="severity",nullable=false,length=16) private EmergencyIncidentSeverity severity;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=24) private EmergencyIncidentStatus status;
    @Column(name="description",nullable=false,columnDefinition="TEXT") private String description;
    @Column(name="x_m",precision=12,scale=4) private BigDecimal xM;
    @Column(name="y_m",precision=12,scale=4) private BigDecimal yM;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;
    @Column(name="resolved_at") private Instant resolvedAt;
    @Column(name="closed_at") private Instant closedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
}
