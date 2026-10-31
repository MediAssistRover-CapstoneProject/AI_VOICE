package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.StepAuditLevel;
import com.mediassistrover.backend.enums.StepType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="Step") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Step {
    @Id @GeneratedValue @UuidGenerator @Column(name="step_id",columnDefinition="BINARY(16)") private UUID stepId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workflow_id",nullable=false) private Workflow workflow;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="task_id",nullable=false) private Task task;
    @Enumerated(EnumType.STRING) @Column(name="step_type_code",nullable=false,length=40) private StepType stepType;
    @Column(name="step_code",nullable=false,length=80) private String stepCode;
    @Column(name="step_name",nullable=false,length=160) private String stepName;
    @Column(name="sequence_no",nullable=false) private int sequenceNo;
    @Column(name="description",columnDefinition="TEXT") private String description;
    @Column(name="is_enabled",nullable=false) private boolean enabled;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="params_json",nullable=false,columnDefinition="JSON") private JsonNode paramsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="input_mapping_json",nullable=false,columnDefinition="JSON") private JsonNode inputMappingJson;
    @Column(name="timeout_sec",nullable=false) private int timeoutSec;
    @Column(name="max_attempts",nullable=false) private int maxAttempts;
    @Column(name="retry_delay_sec",nullable=false) private int retryDelaySec;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="retry_codes_json",nullable=false,columnDefinition="JSON") private JsonNode retryCodesJson;
    @Column(name="max_visits",nullable=false) private int maxVisits;
    @Enumerated(EnumType.STRING) @Column(name="audit_level",nullable=false,length=16) private StepAuditLevel auditLevel;
    @CreatedDate @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
}
