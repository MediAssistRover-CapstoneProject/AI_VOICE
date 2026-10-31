package com.mediassistrover.backend.dto.verification.response;
import com.mediassistrover.backend.enums.AuthVerificationPurpose;
import java.time.Instant;
import java.util.UUID;
public record QrChallengeResponse(UUID challengeId,UUID jobId,UUID authJobStepId,AuthVerificationPurpose purpose,
                                  String proof,Instant expiresAt) { }
