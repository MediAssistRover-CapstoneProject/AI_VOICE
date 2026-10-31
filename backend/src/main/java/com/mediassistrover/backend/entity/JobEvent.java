package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.JobEventProducerType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="JobEvent") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JobEvent {
    @Id @Column(name="job_event_id",columnDefinition="BINARY(16)") private UUID jobEventId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id",nullable=false) private Job job;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="robot_id") private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_task_id") private JobTask jobTask;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_step_id") private JobStep jobStep;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="actor_account_id") private Account actorAccount;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="consumed_by_job_step_id") private JobStep consumedByJobStep;
    @Enumerated(EnumType.STRING) @Column(name="producer_type",nullable=false,length=24) private JobEventProducerType producerType;
    @Column(name="producer_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID producerSessionId;
    @Column(name="sequence_no",nullable=false) private long sequenceNo;
    @Column(name="event_type",nullable=false,length=100) private String eventType;
    @Column(name="correlation_key",length=150) private String correlationKey;
    @Column(name="attempt_no") private Integer attemptNo;
    @Column(name="previous_status",length=32) private String previousStatus;
    @Column(name="new_status",length=32) private String newStatus;
    @Column(name="status_scope",length=24) private String statusScope;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="event_data_json",nullable=false,columnDefinition="JSON") private JsonNode eventDataJson;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
    @Column(name="persisted_at",nullable=false) private Instant persistedAt;
    @Column(name="consumed_at") private Instant consumedAt;
}
