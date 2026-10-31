package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity @Table(name="StepTransition")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class StepTransition {
    @Id @GeneratedValue @UuidGenerator @Column(name="transition_id",columnDefinition="BINARY(16)") private UUID transitionId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="workflow_id",nullable=false) private Workflow workflow;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="from_step_id",nullable=false) private Step fromStep;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="to_step_id") private Step toStep;
    @Enumerated(EnumType.STRING) @Column(name="outcome",nullable=false,length=16) private TransitionOutcome outcome;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="condition_json",columnDefinition="JSON") private JsonNode conditionJson;
    @Column(name="priority",nullable=false) private int priority;
    @Column(name="is_default",nullable=false) private boolean defaultTransition;
    @Enumerated(EnumType.STRING) @Column(name="terminal_status",length=32) private TerminalStatus terminalStatus;
}
