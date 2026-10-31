package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.JobStepStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="JobStep") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JobStep {
    @Id @GeneratedValue @UuidGenerator @Column(name="job_step_id",columnDefinition="BINARY(16)") private UUID jobStepId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_task_id",nullable=false) private JobTask jobTask;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="step_id",nullable=false) private Step step;
    @Column(name="visit_no",nullable=false) private int visitNo;
    @Column(name="execution_sequence_no",nullable=false) private long executionSequenceNo;
    @Column(name="step_code_snapshot",nullable=false,length=80) private String stepCodeSnapshot;
    @Column(name="step_type_snapshot",nullable=false,length=40) private String stepTypeSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="resolved_inputs_json",nullable=false,columnDefinition="JSON") private JsonNode resolvedInputsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="result_json",columnDefinition="JSON") private JsonNode resultJson;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=24) private JobStepStatus status;
    @Column(name="attempt_no",nullable=false) private int attemptNo;
    @Column(name="operation_key",nullable=false,length=150,unique=true) private String operationKey;
    @Column(name="timeout_sec_snapshot",nullable=false) private int timeoutSecSnapshot;
    @Column(name="selected_at") private Instant selectedAt;
    @Column(name="mapping_cleanup",nullable=false) private boolean mappingCleanup;
    @Column(name="interruption_reason",length=32) private String interruptionReason;
    @Column(name="interruption_requested_at") private Instant interruptionRequestedAt;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="ended_at") private Instant endedAt;
    @Column(name="error_code",length=100) private String errorCode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="error_details_json",columnDefinition="JSON") private JsonNode errorDetailsJson;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
}
