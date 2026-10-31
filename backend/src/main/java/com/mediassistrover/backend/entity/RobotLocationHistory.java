package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="RobotLocationHistory")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RobotLocationHistory {
    @Id @Column(name="robot_location_history_id",columnDefinition="BINARY(16)") private UUID robotLocationHistoryId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="robot_id",nullable=false) private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id") private Job job;
    @Column(name="x_m",nullable=false,precision=12,scale=4) private BigDecimal xM;
    @Column(name="y_m",nullable=false,precision=12,scale=4) private BigDecimal yM;
    @Column(name="yaw_rad",nullable=false,precision=10,scale=6) private BigDecimal yawRad;
    @Column(name="localization_confidence",precision=5,scale=4) private BigDecimal localizationConfidence;
    @Column(name="frame_id",length=80) private String frameId;
    @Column(name="source",length=80) private String source;
    @Column(name="is_fresh",nullable=false) private boolean fresh;
    @Column(name="captured_at",nullable=false) private Instant capturedAt;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
}
