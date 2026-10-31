package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.JobTaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="JobTask") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JobTask {
    @Id @GeneratedValue @UuidGenerator @Column(name="job_task_id",columnDefinition="BINARY(16)") private UUID jobTaskId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id",nullable=false) private Job job;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="task_id",nullable=false) private Task task;
    @Column(name="sequence_no",nullable=false) private int sequenceNo;
    @Column(name="task_name_snapshot",nullable=false,length=160) private String taskNameSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="config_snapshot_json",nullable=false,columnDefinition="JSON") private JsonNode configSnapshotJson;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=24) private JobTaskStatus status;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="ended_at") private Instant endedAt;
    @Column(name="error_code",length=100) private String errorCode;
    @Column(name="error_details",columnDefinition="TEXT") private String errorDetails;
}
