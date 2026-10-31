package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.WorkflowStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="Workflow",uniqueConstraints=@UniqueConstraint(name="uq_workflow_code_version",columnNames={"workflow_code","version_no"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Workflow {
    @Id @GeneratedValue @UuidGenerator @Column(name="workflow_id",columnDefinition="BINARY(16)") private UUID workflowId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="start_step_id") private Step startStep;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="published_by_account_id") private Account publishedByAccount;
    @Column(name="workflow_code",nullable=false,length=80) private String workflowCode;
    @Column(name="version_no",nullable=false) private int versionNo;
    @Column(name="workflow_name",nullable=false,length=160) private String workflowName;
    @Column(name="service_type",nullable=false,length=64) private String serviceType;
    @Column(name="description",columnDefinition="TEXT") private String description;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="input_schema_json",nullable=false,columnDefinition="JSON") private JsonNode inputSchemaJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="input_ui_json",nullable=false,columnDefinition="JSON") private JsonNode inputUiJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="default_inputs_json",nullable=false,columnDefinition="JSON") private JsonNode defaultInputsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="output_mapping_json",nullable=false,columnDefinition="JSON") private JsonNode outputMappingJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="required_capabilities_json",nullable=false,columnDefinition="JSON") private JsonNode requiredCapabilitiesJson;
    @Column(name="max_duration_sec",nullable=false) private int maxDurationSec;
    @Column(name="max_total_visits",nullable=false) private int maxTotalVisits;
    @Column(name="graph_version",nullable=false) private int graphVersion;
    @Column(name="schema_version",nullable=false) private int schemaVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="compiled_definition_json",columnDefinition="JSON") private JsonNode compiledDefinitionJson;
    @Column(name="definition_hash",length=64,columnDefinition="CHAR(64)") private String definitionHash;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=24) private WorkflowStatus status;
    @Column(name="accepts_new_jobs",nullable=false) private boolean acceptsNewJobs;
    @Column(name="published_at") private Instant publishedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    @CreatedDate @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @LastModifiedDate @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
