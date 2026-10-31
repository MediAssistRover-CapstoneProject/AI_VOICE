package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="Alert")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Alert {
    @Id @GeneratedValue @UuidGenerator @Column(name="alert_id",columnDefinition="BINARY(16)") private UUID alertId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="incident_id") private EmergencyIncident incident;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="detection_event_id") private DetectionEvent detectionEvent;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_step_id") private JobStep jobStep;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="service_request_id") private ServiceRequest serviceRequest;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="zone_id") private Zone zone;
    @Column(name="notification_type",nullable=false,length=100) private String notificationType;
    @Enumerated(EnumType.STRING) @Column(name="severity",nullable=false,length=16) private AlertSeverity severity;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=24) private AlertStatus status;
    @Column(name="message",nullable=false,columnDefinition="TEXT") private String message;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="data_json",nullable=false,columnDefinition="JSON") private JsonNode dataJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="requested_recipients_json",nullable=false,columnDefinition="JSON") private JsonNode requestedRecipientsJson;
    @Column(name="require_ack",nullable=false) private boolean requireAck;
    @Enumerated(EnumType.STRING) @Column(name="ack_policy",nullable=false,length=16) private AlertAckPolicy ackPolicy;
    @Column(name="operation_key",nullable=false,length=150,unique=true) private String operationKey;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="expires_at") private Instant expiresAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
}
