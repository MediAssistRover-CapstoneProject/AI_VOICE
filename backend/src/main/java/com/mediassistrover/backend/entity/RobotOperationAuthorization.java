package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** BE binding. Pi must additionally enforce this grant against the command's operation envelope. */
@Entity @Table(name="RobotOperationAuthorization")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RobotOperationAuthorization {
    @Id @GeneratedValue @UuidGenerator @Column(name="authorization_id",columnDefinition="BINARY(16)") private UUID authorizationId;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="verification_id",nullable=false,unique=true) private AuthVerification verification;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="job_step_id",nullable=false,unique=true) private JobStep jobStep;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="robot_id",nullable=false) private Robot robot;
    @Column(name="operation_key",nullable=false,length=150,unique=true) private String operationKey;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="authorization_json",nullable=false,columnDefinition="JSON") private JsonNode authorizationJson;
    @Column(name="issued_at",nullable=false) private Instant issuedAt;
    @Column(name="valid_until",nullable=false) private Instant validUntil;
}
