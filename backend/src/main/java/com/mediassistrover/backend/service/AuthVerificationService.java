package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests.Create;
import com.mediassistrover.backend.dto.verification.response.AuthVerificationResponse;
import com.mediassistrover.backend.entity.AuthVerification;
import com.mediassistrover.backend.enums.AuthVerificationPurpose;
import com.mediassistrover.backend.security.AccountPrincipal;
import java.util.UUID;
public interface AuthVerificationService {
    AuthVerificationResponse create(UUID jobId,Create request,AccountPrincipal actor,RequestMetadata metadata);
    AuthVerificationResponse get(UUID id,AccountPrincipal actor);
    AuthVerification requireUsable(UUID id,UUID requestId,AuthVerificationPurpose purpose,AccountPrincipal actor);
}
