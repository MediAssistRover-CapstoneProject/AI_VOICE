package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="JobNavigationSelection") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JobNavigationSelection {
    @Id @GeneratedValue @UuidGenerator @Column(name="selection_id",columnDefinition="BINARY(16)") private UUID selectionId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="resolve_job_step_id",nullable=false) private JobStep resolveJobStep;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="selector_job_step_id",nullable=false,unique=true) private JobStep selectorJobStep;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="move_job_step_id",unique=true) private JobStep moveJobStep;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="endpoint_group_id",nullable=false) private EndpointGroup endpointGroup;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="endpoint_id",nullable=false) private Endpoint endpoint;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="target_snapshot_json",nullable=false,columnDefinition="JSON") private JsonNode targetSnapshotJson;
    @Column(name="target_index",nullable=false) private int targetIndex;
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
