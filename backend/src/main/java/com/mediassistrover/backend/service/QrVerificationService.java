package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.verification.request.QrVerificationRequests.Issue;
import com.mediassistrover.backend.dto.verification.response.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import java.util.UUID;
public interface QrVerificationService {
    QrChallengeResponse issue(UUID jobId,Issue request,AccountPrincipal actor,RequestMetadata metadata);
    AuthVerificationResponse verify(UUID jobId,String proof,AccountPrincipal actor,RequestMetadata metadata);
}
