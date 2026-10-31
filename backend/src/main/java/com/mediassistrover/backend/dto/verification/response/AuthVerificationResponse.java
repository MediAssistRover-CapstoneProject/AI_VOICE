package com.mediassistrover.backend.dto.verification.response;
import com.mediassistrover.backend.enums.*;
import java.time.Instant;
import java.util.UUID;
public record AuthVerificationResponse(UUID verificationId,UUID jobId,UUID authJobStepId,UUID actorAccountId,
                                       UUID usedByJobStepId,AuthVerificationMethod method,
                                       AuthVerificationPurpose purpose,String requiredPermission,
                                       Instant issuedAt,Instant validUntil,Instant revokedAt) { }
