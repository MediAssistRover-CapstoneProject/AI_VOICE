package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.AuthVerificationPurpose;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="QrChallenge")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QrChallenge {
    @Id @GeneratedValue @UuidGenerator @Column(name="challenge_id",columnDefinition="BINARY(16)") private UUID challengeId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="job_id",nullable=false) private Job job;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="auth_job_step_id",nullable=false,unique=true) private JobStep authJobStep;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="used_by_account_id") private Account usedByAccount;
    @Enumerated(EnumType.STRING) @Column(name="purpose",nullable=false,length=32) private AuthVerificationPurpose purpose;
    @Column(name="token_hash",nullable=false,length=64,columnDefinition="CHAR(64)",unique=true) private String tokenHash;
    @Column(name="issued_at",nullable=false) private Instant issuedAt;
    @Column(name="expires_at",nullable=false) private Instant expiresAt;
    @Column(name="used_at") private Instant usedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
}
