package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.RobotStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="RobotStatusHistory")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RobotStatusHistory {
    @Id @Column(name="robot_status_history_id",columnDefinition="BINARY(16)") private UUID robotStatusHistoryId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="robot_id",nullable=false) private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id") private Job job;
    @Enumerated(EnumType.STRING) @Column(name="previous_status",length=32) private RobotStatus previousStatus;
    @Enumerated(EnumType.STRING) @Column(name="new_status",nullable=false,length=32) private RobotStatus newStatus;
    @Column(name="reason",columnDefinition="TEXT") private String reason;
    @Column(name="error_code",length=100) private String errorCode;
    @Column(name="recorded_at",nullable=false) private Instant recordedAt;
}
