package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="Task") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Task {
    @Id @GeneratedValue @UuidGenerator @Column(name="task_id",columnDefinition="BINARY(16)") private UUID taskId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workflow_id",nullable=false) private Workflow workflow;
    @Column(name="task_code",nullable=false,length=80) private String taskCode;
    @Column(name="task_name",nullable=false,length=160) private String taskName;
    @Column(name="sequence_no",nullable=false) private int sequenceNo;
    @Column(name="description",columnDefinition="TEXT") private String description;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="config_json",nullable=false,columnDefinition="JSON") private JsonNode configJson;
    @CreatedDate @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
}
