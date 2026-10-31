package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
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

@Entity @Table(name="Schedule") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Schedule {
    @Id @GeneratedValue @UuidGenerator @Column(name="schedule_id",columnDefinition="BINARY(16)") private UUID scheduleId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="workflow_id",nullable=false) private Workflow workflow;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="robot_id") private Robot robot;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="created_by_account_id") private Account createdByAccount;
    @Column(name="schedule_code",nullable=false,length=64,unique=true) private String scheduleCode;
    @Column(name="schedule_name",nullable=false,length=160) private String scheduleName;
    @Enumerated(EnumType.STRING) @Column(name="schedule_type",nullable=false,length=24) private ScheduleType scheduleType;
    @Column(name="timezone",nullable=false,length=64) private String timezone;
    @Column(name="starts_at",nullable=false) private Instant startsAt;
    @Column(name="ends_at") private Instant endsAt;
    @Column(name="interval_sec") private Integer intervalSec;
    @Column(name="cron_expression",length=120) private String cronExpression;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="input_template_json",nullable=false,columnDefinition="JSON") private JsonNode inputTemplateJson;
    @Column(name="priority",nullable=false) private int priority;
    @Enumerated(EnumType.STRING) @Column(name="misfire_policy",nullable=false,length=24) private MisfirePolicy misfirePolicy;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=16) private ScheduleStatus status;
    @Column(name="next_run_at") private Instant nextRunAt;
    @Column(name="last_run_at") private Instant lastRunAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    @CreatedDate @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @LastModifiedDate @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
