package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="AuthVerification")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AuthVerification {
    @Id @GeneratedValue @UuidGenerator @Column(name="verification_id",columnDefinition="BINARY(16)") private UUID verificationId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="job_id",nullable=false) private Job job;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="auth_job_step_id",nullable=false,unique=true) private JobStep authJobStep;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="actor_account_id",nullable=false) private Account actorAccount;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="used_by_job_step_id",unique=true) private JobStep usedByJobStep;
    @Enumerated(EnumType.STRING) @Column(name="method",nullable=false,length=32) private AuthVerificationMethod method;
    @Enumerated(EnumType.STRING) @Column(name="purpose",nullable=false,length=32) private AuthVerificationPurpose purpose;
    @Column(name="required_permission",nullable=false,length=80) private String requiredPermission;
    @Column(name="issued_at",nullable=false) private Instant issuedAt;
    @Column(name="valid_until",nullable=false) private Instant validUntil;
    @Column(name="revoked_at") private Instant revokedAt;
    @Column(name="actor_token_version") private Long actorTokenVersion;
}
