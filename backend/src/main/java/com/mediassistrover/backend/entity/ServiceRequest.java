package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ServiceRequest") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceRequest {
    @Id @GeneratedValue @UuidGenerator @Column(name="service_request_id",columnDefinition="BINARY(16)") private UUID serviceRequestId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="requested_by_account_id") private Account requestedByAccount;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="requested_robot_id") private Robot requestedRobot;
    @Column(name="related_incident_id",columnDefinition="BINARY(16)") private UUID relatedIncidentId;
    @Column(name="request_code",nullable=false,length=64,unique=true) private String requestCode;
    @Enumerated(EnumType.STRING) @Column(name="request_type",nullable=false,length=32) private ServiceRequestType requestType;
    @Enumerated(EnumType.STRING) @Column(name="source_type",nullable=false,length=24) private RequestSourceType sourceType;
    @Column(name="priority",nullable=false) private int priority;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=32) private ServiceRequestStatus status;
    @Column(name="request_note",columnDefinition="TEXT") private String requestNote;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="request_data_json",nullable=false,columnDefinition="JSON") private JsonNode requestDataJson;
    @Column(name="idempotency_key",nullable=false,length=150,unique=true) private String idempotencyKey;
    @Column(name="requested_at",nullable=false,updatable=false) private Instant requestedAt;
    @Column(name="scheduled_at") private Instant scheduledAt;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @Column(name="completed_at") private Instant completedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    @LastModifiedDate @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
